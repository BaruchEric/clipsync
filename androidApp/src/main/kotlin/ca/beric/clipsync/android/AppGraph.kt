package ca.beric.clipsync.android

import android.content.Context
import android.os.Build
import android.util.Log
import ca.beric.clipsync.android.capture.AndroidClipboardApplier
import ca.beric.clipsync.android.capture.ShizukuClipboard
import ca.beric.clipsync.android.capture.ShizukuClipboardSource
import ca.beric.clipsync.core.Clip
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.core.ClipboardWatcher
import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.discovery.NsdDiscovery
import ca.beric.clipsync.db.DriverFactory
import ca.beric.clipsync.identity.DeviceIdentity
import ca.beric.clipsync.identity.SecretStore
import ca.beric.clipsync.pairing.PairingManager
import ca.beric.clipsync.pairing.PeerStore
import ca.beric.clipsync.sync.SyncEngine
import ca.beric.clipsync.transport.ConnectionManager
import ca.beric.clipsync.transport.PeerDialer
import ca.beric.clipsync.transport.TlsIdentityStore
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process-wide singletons and the Android sync wiring. Initialized from [ClipsyncApp]
 * and services. [startSync] builds the device identity, sync engine, and a client-only
 * [ConnectionManager] (the phone dials the desktop; it does not serve for the MVP sim),
 * then starts capture and the dial loop.
 */
object AppGraph {
    private const val TAG = "clipsyncGraph"

    lateinit var repo: ClipRepository
        private set
    lateinit var peerStore: PeerStore
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var db: ClipsyncDb
    private var shizuku: ShizukuClipboard? = null

    @Volatile
    private var pairingManager: PairingManager? = null

    @Volatile
    private var connectionManager: ConnectionManager? = null

    /** This device's pairing payload (X25519 pubkey etc.), ready once [startSync] completes. */
    @Volatile
    var myPayload: String? = null
        private set

    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())

    /** Live set of connected peer device ids, for the status UI. */
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers

    fun init(context: Context) {
        if (::repo.isInitialized) return
        db = ClipsyncDb(DriverFactory(context.applicationContext).createDriver())
        repo = ClipRepository(db, writeContext = Dispatchers.IO)
        peerStore = PeerStore(db)
    }

    @Synchronized
    fun startSync(context: Context) {
        if (syncStarted) return
        syncStarted = true
        val appContext = context.applicationContext
        init(appContext)
        val clipboard = ShizukuClipboard(appContext).also { shizuku = it }
        scope.launch {
            ClipsyncCrypto.ensureInitialized()
            val secretStore = SecretStore(appContext)
            val identity = DeviceIdentity(db, secretStore).getOrCreate(Build.MODEL ?: "Android")
            // Persisted so the fingerprint is stable; the phone still dials the desktop
            // (client-only) this build — serving is the next unit.
            val tls = runCatching {
                TlsIdentityStore(File(appContext.filesDir, "tls.p12"), secretStore).loadOrCreate("clipsync-android")
            }.onFailure { Log.w(TAG, "TLS identity unavailable: ${it.message}") }.getOrNull()
            val engine = SyncEngine(identity.deviceId, repo, AndroidClipboardApplier(clipboard))
            val pairing = PairingManager(identity, peerStore)
            pairingManager = pairing
            val manager = ConnectionManager(
                localDeviceId = identity.deviceId,
                tlsIdentity = tls, // now serves too (symmetric P2P) when a cert is available
                engine = engine,
                perPairKeyFor = { peerStore.get(it)?.perPairKey },
                scope = scope,
                // myPayload is set just below (after serving is known); the lambda reads it lazily.
                myPayload = { myPayload },
                pairingSink = { json -> pairing.pair(json, System.currentTimeMillis())?.deviceId },
            )
            connectionManager = manager
            // Netty-on-Android is the least-proven thing here: a server failure must not
            // kill the process — the dial path still works.
            val serving = tls != null && runCatching { manager.startServer(SYNC_PORT) }
                .onFailure { Log.w(TAG, "server start failed: ${it.message}") }.isSuccess
            myPayload = pairing.myPayload(
                certFingerprint = tls?.fingerprint ?: CLIENT_NO_CERT,
                addresses = if (serving) localAddresses() else emptyList(),
                port = if (serving) SYNC_PORT else 0,
            )
            Log.i(TAG, "clipsync-payload serving=$serving $myPayload")

            startCapture(engine, clipboard)
            PeerDialer(manager, peerStore, scope).start()
            launch { manager.connectedPeers.collect { _connectedPeers.value = it } }
            // mDNS only when we serve (advertising a closed port would mislead peers).
            if (serving) {
                runCatching {
                    NsdDiscovery(appContext).start(identity.deviceId, SYNC_PORT) { found ->
                        val peer = peerStore.get(found.deviceId) ?: return@start
                        scope.launch {
                            manager.dialPeer(found.deviceId, listOf("${found.host}:${found.port}"), peer.certFingerprint)
                        }
                    }
                }.onFailure { Log.w(TAG, "mDNS unavailable: ${it.message}") }
            }
        }
    }

    /** Non-loopback IPv4 addresses (Wi-Fi LAN + tailnet 100.x) to advertise for dial-in. */
    private fun localAddresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())

    /**
     * Import a peer's pairing payload and persist it. Suspends briefly until the sync
     * layer has finished initializing (identity ready), then derives the per-pair key.
     */
    suspend fun pair(peerPayloadText: String): Boolean {
        var pm = pairingManager
        var waited = 0
        while (pm == null && waited < 100) {
            delay(50)
            pm = pairingManager
            waited++
        }
        val manager = pm ?: run {
            Log.w(TAG, "pair() gave up waiting for sync init")
            return false
        }
        val peer = manager.pair(peerPayloadText, System.currentTimeMillis())
        if (peer == null) {
            Log.w(TAG, "pair() rejected payload")
            return false
        }
        Log.i(TAG, "paired ${peer.deviceId} name=${peer.deviceName} endpoints=${peer.addresses}")
        return true
    }

    /**
     * Pair from a scanned QR payload, then arm reciprocal pairing so the next outbound link
     * sends our payload back — the scanned (camera-less) peer needs it to derive the same key.
     */
    suspend fun pairFromScan(payloadText: String): Boolean {
        val ok = pair(payloadText)
        if (ok) connectionManager?.offerReciprocalPairing()
        return ok
    }

    private fun startCapture(engine: SyncEngine, clipboard: ShizukuClipboard) {
        scope.launch {
            ClipboardWatcher(ShizukuClipboardSource(clipboard), pollIntervalMs = 500)
                .changes()
                .collect { clip ->
                    val now = System.currentTimeMillis()
                    when (clip) {
                        is Clip.Text -> {
                            repo.record(LOCAL_DEVICE_ID, clip.text, now)
                            engine.onLocalCapture(clip.text, now)
                        }
                        // Android clipboard image capture (content:// URIs via Shizuku) is not
                        // implemented yet; images received from a peer are handled by the applier.
                        is Clip.Image -> Unit
                    }
                }
        }
    }

    @Volatile
    private var syncStarted = false

    private const val CLIENT_NO_CERT = "android-client-no-cert"
    private const val SYNC_PORT = 47653
}
