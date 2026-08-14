package ca.beric.clipsync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import ca.beric.clipsync.android.browse.BrowsePrefs
import ca.beric.clipsync.android.browse.MediaIndex
import ca.beric.clipsync.android.browse.ShizukuFileBridge
import ca.beric.clipsync.android.capture.AndroidClipboardApplier
import ca.beric.clipsync.android.capture.NotifMirrorService
import ca.beric.clipsync.android.capture.ShizukuClipboard
import ca.beric.clipsync.android.sms.SmsBridge
import ca.beric.clipsync.android.capture.ShizukuClipboardSource
import ca.beric.clipsync.android.transfer.DispatchingFileSink
import ca.beric.clipsync.android.transfer.MediaStoreFileSink
import ca.beric.clipsync.browse.BrowseEngine
import ca.beric.clipsync.browse.BrowseRoot
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
import ca.beric.clipsync.pairing.PairingPayload
import ca.beric.clipsync.pairing.PeerStore
import ca.beric.clipsync.mirror.MirrorEngine
import ca.beric.clipsync.pairing.pairedLogLine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.sync.SyncEngine
import ca.beric.clipsync.transfer.FileSource
import ca.beric.clipsync.transfer.FileTransferEngine
import ca.beric.clipsync.transfer.TransferState
import ca.beric.clipsync.transport.ConnectionManager
import ca.beric.clipsync.transport.PeerDialer
import ca.beric.clipsync.transport.TlsIdentityStore
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

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

    @Volatile
    private var fileEngine: FileTransferEngine? = null

    private var mirrorEngine: MirrorEngine? = null

    @Volatile
    private var browseEngine: BrowseEngine? = null

    @Volatile
    private var fileBridge: ShizukuFileBridge? = null

    @Volatile
    private var mediaIndex: MediaIndex? = null

    private var smsBridge: SmsBridge? = null

    private var smsObserverStarted = false

    private val _transfers = MutableStateFlow<List<TransferState>>(emptyList())

    /** Recent file transfers (both directions), for the status UI. */
    val transfers: StateFlow<List<TransferState>> = _transfers

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
            val engine = SyncEngine(
                identity.deviceId, repo,
                AndroidClipboardApplier(clipboard, MediaStoreFileSink(appContext)) { name ->
                    notifyFileReceived(appContext, name)
                },
            )
            // Bind only if the user has actually consented. This spawns a SHELL-uid helper
            // process — the app's first persistent one — and standing it up for someone who
            // never turned browsing on is exactly what the toggle exists to prevent. The
            // startup bind covers the already-consented case so the first request is ready;
            // ensureFileBridgeBound() covers the moment consent is granted.
            val bridge = ShizukuFileBridge(appContext).also {
                fileBridge = it
                if (BrowsePrefs.enabled(appContext)) it.bind()
            }
            // Shizuku's user service dies whenever Shizuku restarts, which happens on every
            // phone reboot. bind() at startup alone would leave browsing silently dead until
            // the app is relaunched, so re-bind whenever Shizuku's binder comes back.
            Shizuku.addBinderReceivedListener {
                if (BrowsePrefs.enabled(appContext)) {
                    Log.i(TAG, "shizuku binder received; rebinding file bridge")
                    fileBridge?.bind()
                }
            }
            mediaIndex = MediaIndex(appContext)
            val roots = listOf(
                BrowseRoot("internal", "Internal storage", Environment.getExternalStorageDirectory().absolutePath),
                BrowseRoot("download", "Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath),
                BrowseRoot("documents", "Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath),
                BrowseRoot("dcim", "Camera", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath),
                BrowseRoot("pictures", "Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath),
                BrowseRoot("movies", "Movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath),
                BrowseRoot("music", "Music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath),
            )
            val browse = BrowseEngine(
                scope = scope,
                bridge = bridge,
                roots = roots,
                enabled = { BrowsePrefs.enabled(appContext) },
                log = { Log.i(TAG, it) },
            )
            browseEngine = browse
            val files = FileTransferEngine(
                scope,
                DispatchingFileSink(
                    MediaStoreFileSink(appContext),
                    bridge,
                    confine = { browse.confineAbsolute(it) },
                    // The moment a browse push actually lands at its final path — see
                    // rescanAfterMutation's kdoc for why onPush's own FsResult can't drive this.
                    onPublished = { target -> rescan(appContext, listOf(target)) },
                ),
                log = { Log.i(TAG, it) },
            )
            browse.transfers = files
            files.onFileReceived = { name, _ -> notifyFileReceived(appContext, name) }
            fileEngine = files
            launch { files.transfers.collect { _transfers.value = it } }
            val sms = SmsBridge(appContext)
            smsBridge = sms
            val mirrorE = MirrorEngine(
                onEvent = { from, event -> handleMirrorEvent(appContext, from, event) },
                log = { Log.i(TAG, it) },
            )
            mirrorEngine = mirrorE
            val pairing = PairingManager(identity, peerStore)
            pairingManager = pairing
            // Set after startServer decides whether this device serves; the Hello lambda
            // reads it per link, so endpoints are only advertised once genuinely dialable.
            var servingFlag = false
            val manager = ConnectionManager(
                localDeviceId = identity.deviceId,
                tlsIdentity = tls, // now serves too (symmetric P2P) when a cert is available
                engine = engine,
                perPairKeyFor = { peerStore.get(it)?.perPairKey },
                scope = scope,
                // myPayload is set just below (after serving is known); the lambda reads it lazily.
                myPayload = { myPayload },
                // Same SAS log as the local/scan path: a peer that pairs us over the wire must
                // leave the same evidence, or an on-device run reports "never derived a key".
                pairingSink = { json ->
                    pairing.pair(json, System.currentTimeMillis())?.let { peer ->
                        Log.i(TAG, peer.pairedLogLine(via = "wire"))
                        peer.deviceId
                    }
                },
                fileEngine = files,
                mirror = mirrorE,
                myEndpoints = { if (servingFlag) localAddresses().map { a -> "$a:$SYNC_PORT" } else emptyList() },
                endpointSink = { id, eps ->
                    peerStore.updateAddresses(id, eps)
                    Log.i(TAG, "endpoints refreshed for $id -> $eps")
                },
            )
            connectionManager = manager
            // Netty-on-Android is the least-proven thing here: a server failure must not
            // kill the process — the dial path still works.
            val serving = tls != null && runCatching { manager.startServer(SYNC_PORT) }
                .onFailure { Log.w(TAG, "server start failed: ${it.message}") }.isSuccess
            servingFlag = serving
            myPayload = pairing.myPayload(
                certFingerprint = tls?.fingerprint ?: CLIENT_NO_CERT,
                addresses = if (serving) localAddresses() else emptyList(),
                port = if (serving) SYNC_PORT else 0,
            )
            Log.i(TAG, "clipsync-payload serving=$serving $myPayload")

            startCapture(engine, clipboard)
            startSmsObserverIfGranted()
            PeerDialer(manager, peerStore, scope).start()
            launch { manager.connectedPeers.collect { _connectedPeers.value = it } }
            // mDNS only when we serve (advertising a closed port would mislead peers).
            if (serving) {
                runCatching {
                    NsdDiscovery(appContext).start(identity.deviceId, SYNC_PORT) { found ->
                        val peer = peerStore.get(found.deviceId) ?: return@start
                        if (manager.isConnected(found.deviceId)) return@start // resolver can re-fire
                        // Logged so an on-device run can attribute a connect to mDNS vs. the dialer.
                        Log.i(TAG, "mDNS discovered ${found.deviceId} at ${found.host}:${found.port}; dialing")
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
        // SAS in the log so an on-device pairing run can assert both screens agree without
        // re-deriving the hash outside the app. It is a public comparison code, not key material.
        Log.i(TAG, peer.pairedLogLine(via = "local"))
        return true
    }

    /**
     * Pair from a scanned QR payload, then arm reciprocal pairing so the next outbound link
     * sends our payload back — the scanned (camera-less) peer needs it to derive the same key.
     */
    suspend fun pairFromScan(payloadText: String): Boolean {
        val ok = pair(payloadText)
        if (ok) {
            // Target the scanned device specifically: pairing completes (or refreshes) even
            // when other peers connect in between, and immediately if it's already linked.
            PairingPayload.decode(payloadText)?.deviceId
                ?.let { connectionManager?.offerReciprocalPairing(it) }
        }
        return ok
    }

    /**
     * Streams share-sheet content to connected peers, sequentially (progress stays legible).
     * Returns how many files started sending — 0 means no peers or nothing usable. Waits
     * briefly for [startSync] to finish, mirroring [pair].
     */
    suspend fun sendSharedFiles(context: Context, uris: List<Uri>): Int {
        var engine = fileEngine
        var waited = 0
        while (engine == null && waited < 100) {
            delay(50)
            engine = fileEngine
            waited++
        }
        val files = engine ?: run {
            Log.w(TAG, "sendSharedFiles gave up waiting for sync init")
            return 0
        }
        awaitPeerConnected()
        val resolver = context.applicationContext.contentResolver
        var sent = 0
        for (uri in uris) {
            val source = runCatching { sourceFor(resolver, uri) }.getOrNull()
            if (source == null) {
                Log.w(TAG, "share: cannot resolve $uri (no size/name)")
                continue
            }
            if (files.sendFile(source)) sent++ else Log.w(TAG, "share: no peers connected")
        }
        return sent
    }

    /** Harness hook: put [uriString] on the system clipboard as an image clip (via Shizuku). */
    suspend fun setImageClip(uriString: String): Boolean {
        var s = shizuku
        var waited = 0
        while (s == null && waited < 100) {
            delay(50)
            s = shizuku
            waited++
        }
        return s?.setImageUri(Uri.parse(uriString)) ?: false
    }

    /**
     * Harness hook: put [text] on the system clipboard as if another app copied it (via
     * Shizuku, not the applier — so the poller treats it as a genuine local capture).
     */
    suspend fun setTextClip(text: String): Boolean {
        var s = shizuku
        var waited = 0
        while (s == null && waited < 100) {
            delay(50)
            s = shizuku
            waited++
        }
        return s?.setText(text) ?: false
    }

    /**
     * Harness hook: one Shizuku clipboard read, summarized (kind/length only, no content) —
     * for bisecting capture failures (e.g. does the read see anything while the keyguard is up?).
     */
    suspend fun debugReadClip(): String {
        var s = shizuku
        var waited = 0
        while (s == null && waited < 100) {
            delay(50)
            s = shizuku
            waited++
        }
        if (s == null) return "shizuku=null"
        val sig = s.clipSignature()
        val text = s.readText()
        return "sig=${sig?.toString(16)} text=${text?.let { "len=${it.length}" } ?: "null"}"
    }

    /** Harness twin of [sendSharedFiles]: streams an app-readable filesystem path to peers. */
    suspend fun sendLocalFile(path: String): Boolean {
        var engine = fileEngine
        var waited = 0
        while (engine == null && waited < 100) {
            delay(50)
            engine = fileEngine
            waited++
        }
        val files = engine ?: return false
        awaitPeerConnected()
        val f = File(path)
        if (!f.isFile) return false
        val source = FileSource(f.name, f.length(), "application/octet-stream") { f.inputStream() }
        return files.sendFile(source)
    }

    /**
     * A share often cold-starts the process (share-sheet → activity → startSync), so the
     * dialer may still be connecting when the send is requested. Waiting here turns
     * "first share after a while always fails" into a short pause. 20 s covers a stored
     * peer row whose stale endpoints each burn the 3 s dial timeout before the live one
     * (observed on the S24: reconnect took >10 s until the dial timeout was capped).
     */
    private suspend fun awaitPeerConnected(timeoutMs: Long = 20_000) {
        withTimeoutOrNull(timeoutMs) { connectedPeers.first { it.isNotEmpty() } }
            ?: Log.w(TAG, "no peer connected after ${timeoutMs}ms; sending anyway (will no-op)")
    }

    /** Resolves a content Uri's display name, exact size, and mime into a streamable source. */
    private fun sourceFor(resolver: android.content.ContentResolver, uri: Uri): FileSource? {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { i -> if (!c.isNull(i)) name = c.getString(i) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.let { i -> if (!c.isNull(i)) size = c.getLong(i) }
                }
            }
        if (size < 0) {
            size = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }
        // The offer carries an exact size/chunk count, so a stream of unknown length can't be sent.
        if (size < 0) return null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return FileSource(name, size, mime) {
            resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        }
    }

    private fun notifyFileReceived(context: Context, name: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(FILE_CHANNEL_ID, "Received files", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val openDownloads = PendingIntent.getActivity(
                context, 0,
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                name.hashCode(),
                Notification.Builder(context, FILE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Received $name")
                    .setContentText("Saved to Download/clipsync")
                    .setContentIntent(openDownloads)
                    .setAutoCancel(true)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "file-received notification failed: ${it.message}") }
    }

    /** Called by [NotifMirrorService] for each mirrorable notification. */
    fun mirrorNotification(event: MirrorEvent.NotifPosted) {
        scope.launch { mirrorEngine?.send(null, event) }
    }

    /** Re-checks SMS grants (the observer starts only once they exist). */
    fun onSmsPermissionsChanged() {
        startSmsObserverIfGranted()
    }

    /** Called when the user turns browsing on: binds the SHELL-uid bridge now rather than at next launch. */
    fun ensureFileBridgeBound(context: Context) {
        if (BrowsePrefs.enabled(context)) fileBridge?.bind()
    }

    /**
     * Inbound mirror traffic (desktop → phone). Runs on the transport reader, so every
     * branch dispatches to [scope] immediately.
     */
    private fun handleMirrorEvent(context: Context, from: String, event: MirrorEvent) {
        when (event) {
            is MirrorEvent.NotifReply -> scope.launch {
                val ok = NotifMirrorService.sendReply(context, event.key, event.text)
                Log.i(TAG, "notif reply key=${event.key} ok=$ok")
            }
            is MirrorEvent.SmsQueryThreads -> scope.launch {
                smsBridge?.takeIf { it.hasPermissions() }?.let {
                    mirrorEngine?.send(from, MirrorEvent.SmsThreads(it.threads()))
                }
            }
            is MirrorEvent.SmsQueryThread -> scope.launch {
                smsBridge?.takeIf { it.hasPermissions() }?.let {
                    mirrorEngine?.send(from, MirrorEvent.SmsMessages(event.threadId, it.messages(event.threadId)))
                }
            }
            is MirrorEvent.SmsSend -> scope.launch {
                val bridge = smsBridge
                val ok = bridge != null && bridge.hasPermissions() && bridge.send(event.to, event.body)
                mirrorEngine?.send(from, MirrorEvent.SmsSent(ok, event.to))
                Log.i(TAG, "sms send ok=$ok len=${event.body.length}")
            }
            // Three outcomes, not two: "browsing off", "photos not granted", and real data.
            // An empty MediaItems would collapse the middle case into "you have no photos",
            // and the desktop's Files tab is specified to name the actual cause.
            is MirrorEvent.MediaQuery -> scope.launch(Dispatchers.IO) {
                val index = mediaIndex
                val reply = when {
                    !BrowsePrefs.enabled(context) -> MirrorEvent.FsResult("media", false, "browsing disabled")
                    index == null || !index.hasPermission() ->
                        MirrorEvent.FsResult("media", false, "photo permission not granted")
                    else -> MirrorEvent.MediaItems(index.items(event.offset, event.limit))
                }
                mirrorEngine?.send(from, reply)
            }
            is MirrorEvent.ThumbQuery -> scope.launch(Dispatchers.IO) {
                val index = mediaIndex
                val reply = when {
                    !BrowsePrefs.enabled(context) -> MirrorEvent.FsResult("thumbs", false, "browsing disabled")
                    index == null || !index.hasPermission() ->
                        MirrorEvent.FsResult("thumbs", false, "photo permission not granted")
                    else -> MirrorEvent.Thumbs(index.thumbs(event.ids))
                }
                mirrorEngine?.send(from, reply)
            }
            is MirrorEvent.FsQueryRoots, is MirrorEvent.FsQueryList, is MirrorEvent.FsPull,
            is MirrorEvent.FsPush, is MirrorEvent.FsDelete, is MirrorEvent.FsRename -> scope.launch(Dispatchers.IO) {
                val engine = browseEngine
                val result = engine?.onEvent(from, event)
                // Spec requires a MediaStore rescan after every mutation (line 150-151) so the
                // phone's own gallery reflects it immediately; without this, a deleted photo
                // stays visible in Google Photos until the next scan and reads as "delete
                // didn't work". Rescanning the affected *directory* is enough and is cheaper to
                // wire than threading exact touched paths back through BrowseEngine. Push is
                // handled separately — see rescanAfterMutation's kdoc.
                if (engine != null && result is MirrorEvent.FsResult && result.ok) {
                    rescanAfterMutation(context, engine, event)
                }
                result?.let { mirrorEngine?.send(from, it) }
            }
            // Phone → desktop kinds arriving here would be another phone; nothing to do. The
            // rest are the browse *response* types this device only ever sends, never receives.
            is MirrorEvent.NotifPosted, is MirrorEvent.SmsThreads,
            is MirrorEvent.SmsMessages, is MirrorEvent.SmsSent,
            is MirrorEvent.FsRoots, is MirrorEvent.FsEntries, is MirrorEvent.MediaItems,
            is MirrorEvent.Thumbs, is MirrorEvent.FsResult,
            -> Unit
        }
    }

    /**
     * Directories touched by a successful delete/rename, for [MediaScannerConnection.scanFile].
     * Rename is same-directory only (spec), so the parent of the old path is also the parent of
     * the new one — no need to read the new name back out of [result].
     *
     * Push is deliberately absent here: `onPush`'s FsResult fires when the destination
     * directory is merely confirmed/created, before any bytes arrive, so scanning
     * [result].detail at that point would scan a directory that doesn't yet contain the file.
     * Push is rescanned instead at [DispatchingFileSink]'s `onPublished` callback, the moment
     * the received file is actually at its final path.
     */
    private fun rescanAfterMutation(
        context: Context,
        engine: BrowseEngine,
        event: MirrorEvent,
    ) {
        val dirs = when (event) {
            is MirrorEvent.FsDelete -> event.paths
                .mapNotNull { engine.resolve(event.root, it.substringBeforeLast('/', "")) }
                .distinct()
            is MirrorEvent.FsRename ->
                listOfNotNull(engine.resolve(event.root, event.path.substringBeforeLast('/', "")))
            else -> emptyList()
        }
        rescan(context, dirs)
    }

    /** Shared by [rescanAfterMutation] and the push-publish hook below. */
    private fun rescan(context: Context, paths: List<String>) {
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    @Synchronized
    private fun startSmsObserverIfGranted() {
        val bridge = smsBridge ?: return
        if (smsObserverStarted || !bridge.hasPermissions()) return
        smsObserverStarted = true
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        bridge.observe { ticks.tryEmit(Unit) }
        scope.launch {
            ticks.collectLatest {
                delay(1500) // a burst of provider changes becomes one push
                val sent = mirrorEngine?.send(null, MirrorEvent.SmsThreads(bridge.threads()))
                Log.i(TAG, "sms push sent=$sent")
            }
        }
        Log.i(TAG, "sms observer started")
    }

    private fun startCapture(engine: SyncEngine, clipboard: ShizukuClipboard) {
        scope.launch {
            ClipboardWatcher(ShizukuClipboardSource(clipboard), pollIntervalMs = 500)
                .changes()
                .collect { clip ->
                    val now = System.currentTimeMillis()
                    when (clip) {
                        // Record locally only for a genuine capture (engine returns false on echo).
                        is Clip.Text -> {
                            val genuine = engine.onLocalCapture(clip.text, now)
                            Log.i(TAG, "capture text len=${clip.text.length} genuine=$genuine")
                            if (genuine) repo.record(LOCAL_DEVICE_ID, clip.text, now)
                        }
                        is Clip.Image -> {
                            val genuine = engine.onLocalImageCapture(clip.bytes, clip.mime, now)
                            Log.i(TAG, "capture image ${clip.mime} ${clip.bytes.size}B genuine=$genuine")
                            if (genuine) repo.recordImage(LOCAL_DEVICE_ID, clip.mime, clip.bytes.size, now)
                        }
                    }
                }
        }
    }

    @Volatile
    private var syncStarted = false

    private const val CLIENT_NO_CERT = "android-client-no-cert"
    private const val SYNC_PORT = 47653
    private const val FILE_CHANNEL_ID = "clipsync-files"
}
