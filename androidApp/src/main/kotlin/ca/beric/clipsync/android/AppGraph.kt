package ca.beric.clipsync.android

import android.content.Context
import android.os.Build
import android.util.Log
import ca.beric.clipsync.android.capture.AndroidClipboardApplier
import ca.beric.clipsync.android.capture.ShizukuClipboard
import ca.beric.clipsync.android.capture.ShizukuClipboardSource
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.core.ClipboardWatcher
import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.db.DriverFactory
import ca.beric.clipsync.identity.DeviceIdentity
import ca.beric.clipsync.identity.SecretStore
import ca.beric.clipsync.pairing.PairingManager
import ca.beric.clipsync.pairing.PeerStore
import ca.beric.clipsync.sync.SyncEngine
import ca.beric.clipsync.transport.ConnectionManager
import ca.beric.clipsync.transport.TlsIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
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

    /** This device's pairing payload (X25519 pubkey etc.), ready once [startSync] completes. */
    @Volatile
    var myPayload: String? = null
        private set

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
            val identity = DeviceIdentity(db, SecretStore(appContext))
                .getOrCreate(Build.MODEL ?: "Android")
            // Client-only for the sim: the phone dials the desktop and never serves,
            // so it presents no certificate and needs no TLS identity of its own.
            val engine = SyncEngine(identity.deviceId, repo, AndroidClipboardApplier(clipboard))
            val manager = ConnectionManager(
                localDeviceId = identity.deviceId,
                tlsIdentity = null,
                engine = engine,
                perPairKeyFor = { peerStore.get(it)?.perPairKey },
                scope = scope,
            )
            val pairing = PairingManager(identity, peerStore)
            pairingManager = pairing
            myPayload = pairing.myPayload(
                certFingerprint = CLIENT_NO_CERT,
                addresses = emptyList(), // the desktop dials nothing; the phone has no server
                port = 0,
            )
            Log.i(TAG, "clipsync-payload $myPayload")

            startCapture(engine, clipboard)
            startDialLoop(manager)
        }
    }

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

    private fun startCapture(engine: SyncEngine, clipboard: ShizukuClipboard) {
        scope.launch {
            ClipboardWatcher(ShizukuClipboardSource(clipboard), pollIntervalMs = 500)
                .changes()
                .collect { text ->
                    val now = System.currentTimeMillis()
                    repo.record(LOCAL_DEVICE_ID, text, now)
                    engine.onLocalCapture(text, now)
                }
        }
    }

    private fun startDialLoop(manager: ConnectionManager) {
        scope.launch {
            while (isActive) {
                for (peer in peerStore.all()) {
                    if (manager.isDialing(peer.deviceId)) continue
                    if (peer.addresses.isEmpty()) continue
                    launch { manager.dialPeer(peer.deviceId, peer.addresses, peer.certFingerprint) }
                }
                delay(DIAL_INTERVAL_MS)
            }
        }
    }

    @Volatile
    private var syncStarted = false

    private const val CLIENT_NO_CERT = "android-client-no-cert"
    private const val DIAL_INTERVAL_MS = 3000L
}
