package ca.beric.clipsync.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import ca.beric.clipsync.core.Clip
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.core.ClipboardWatcher
import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import ca.beric.clipsync.core.MacPasteboard
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.discovery.JmDnsDiscovery
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.db.DriverFactory
import ca.beric.clipsync.identity.DeviceIdentity
import ca.beric.clipsync.identity.SecretStore
import ca.beric.clipsync.mirror.MirrorEngine
import ca.beric.clipsync.protocol.FsRoot
import ca.beric.clipsync.protocol.MediaItem
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.protocol.SmsMessage
import ca.beric.clipsync.protocol.SmsThread
import ca.beric.clipsync.pairing.PairingManager
import ca.beric.clipsync.pairing.Peer
import ca.beric.clipsync.pairing.PeerStore
import ca.beric.clipsync.pairing.pairedLogLine
import ca.beric.clipsync.sync.DesktopClipboardApplier
import ca.beric.clipsync.sync.SyncEngine
import ca.beric.clipsync.transfer.FileSource
import ca.beric.clipsync.transfer.FileTransferEngine
import ca.beric.clipsync.transfer.FolderFileSink
import ca.beric.clipsync.transfer.TransferState
import ca.beric.clipsync.transport.ConnectionManager
import ca.beric.clipsync.transport.PeerDialer
import ca.beric.clipsync.transport.TlsIdentityStore
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files

private const val SYNC_PORT = 47653

private fun trayIcon(): BitmapPainter {
    val bitmap = ImageBitmap(64, 64)
    val paint = Paint().apply { color = Color(0xFF2AA198) }
    Canvas(bitmap).drawRect(0f, 0f, 64f, 64f, paint)
    return BitmapPainter(bitmap)
}

/**
 * Non-loopback IPv4 addresses (LAN + tailnet 100.x) to advertise as dial hints. VM bridges
 * (Parallels "vnic"/"bridge" interfaces) are excluded: their dead-end 10.x addresses were
 * advertised ahead of the real LAN address, and every dead endpoint ahead of a live one costs
 * a full dial attempt on each reconnect (HANDOFF 2026-08-08). Tailnet CGNAT addresses sort
 * last so the LAN path is tried first when both are present.
 */
private fun localAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .filterNot {
            it.name.startsWith("vnic") || it.name.startsWith("bridge") ||
                runCatching { it.displayName ?: "" }.getOrDefault("").contains("Parallels", ignoreCase = true)
        }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
        .sortedBy { if (it.startsWith("100.")) 1 else 0 }

fun main() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Single DB/repo shared by the history UI, capture, and the sync engine ---
    val db = ClipsyncDb(DriverFactory().createDriver())
    val repo = ClipRepository(db)
    val peerStore = PeerStore(db)
    val boot = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val secretStore = SecretStore()
        val identity = DeviceIdentity(db, secretStore).getOrCreate("Mac")
        val appSupportDir = File(System.getProperty("user.home"), "Library/Application Support/clipsync")
        val tls = TlsIdentityStore(File(appSupportDir, "tls.p12"), secretStore).loadOrCreate("clipsync-mac")
        val pairing = PairingManager(identity, peerStore)
        val myPayload = pairing.myPayload(tls.fingerprint, localAddresses(), SYNC_PORT)
        val engine = SyncEngine(identity.deviceId, repo, DesktopClipboardApplier())
        // Received files land in ~/Downloads/clipsync; sends stream from wherever they are.
        val fileEngine = FileTransferEngine(
            appScope,
            FolderFileSink(File(System.getProperty("user.home"), "Downloads/clipsync")),
            log = { println("clipsync: $it") },
        )
        // Phone notifications + messages (M7/M8). Content never hits this log — lengths only.
        val phoneNotifs = MutableStateFlow(emptyList<MirrorEvent.NotifPosted>())
        val notifPings = MutableSharedFlow<MirrorEvent.NotifPosted>(extraBufferCapacity = 16)
        val smsThreads = MutableStateFlow(emptyList<SmsThread>())
        val smsMessages = MutableStateFlow(mapOf<Long, List<SmsMessage>>())
        // Phone file/photo browsing (M9).
        val fsRoots = MutableStateFlow(emptyList<FsRoot>())
        val fsEntries = MutableStateFlow<MirrorEvent.FsEntries?>(null)
        val mediaItems = MutableStateFlow(emptyList<MediaItem>())
        val thumbs = MutableStateFlow(emptyMap<Long, String>())
        val fsResults = MutableSharedFlow<MirrorEvent.FsResult>(extraBufferCapacity = 16)
        // Monotonic "a reply arrived" counters. Needed because neither payload can carry that
        // signal on its own: MediaItems(emptyList()) is a legitimate success that looks empty,
        // and FsEntries is a data class, so re-listing an unchanged directory produces a value
        // == the cached one, which MutableStateFlow conflates and never re-emits.
        val fsEpoch = MutableStateFlow(0)
        val mediaEpoch = MutableStateFlow(0)
        val mirrorEngine = MirrorEngine(
            onEvent = { _, event ->
                when (event) {
                    is MirrorEvent.NotifPosted -> {
                        phoneNotifs.update { (listOf(event) + it).take(20) }
                        notifPings.tryEmit(event)
                        println("clipsync: mirror notif from ${event.app} (${event.text.length} chars, reply=${event.canReply})")
                    }
                    is MirrorEvent.SmsThreads -> {
                        smsThreads.value = event.threads
                        println("clipsync: sms threads: ${event.threads.size}")
                    }
                    is MirrorEvent.SmsMessages -> {
                        smsMessages.update { it + (event.threadId to event.messages) }
                        println("clipsync: sms thread ${event.threadId}: ${event.messages.size} messages")
                    }
                    is MirrorEvent.SmsSent -> println("clipsync: sms send ok=${event.ok}")
                    is MirrorEvent.FsRoots -> fsRoots.value = event.roots
                    is MirrorEvent.FsEntries -> {
                        fsEntries.value = event
                        fsEpoch.value += 1
                    }
                    is MirrorEvent.MediaItems -> {
                        mediaItems.value = event.items
                        mediaEpoch.value += 1
                    }
                    is MirrorEvent.Thumbs -> thumbs.value = thumbs.value + event.jpegB64
                    is MirrorEvent.FsResult -> {
                        println("clipsync: fs ${event.op} ok=${event.ok} ${event.detail}")
                        fsResults.tryEmit(event)
                    }
                    else -> Unit // desktop → phone kinds have no meaning inbound
                }
            },
            log = { println("clipsync: $it") },
        )
        val manager = ConnectionManager(
            localDeviceId = identity.deviceId,
            tlsIdentity = tls,
            engine = engine,
            perPairKeyFor = { peerStore.get(it)?.perPairKey },
            scope = appScope,
            myPayload = { myPayload },
            // A phone that scanned this QR sends its payload back; pair it over the wire.
            pairingSink = { json ->
                pairing.pair(json, System.currentTimeMillis())?.let { peer ->
                    // SAS on stdout so an on-device pairing run can assert the codes match
                    // without re-deriving the hash outside the app. via=wire is what proves
                    // reciprocal pairing actually crossed the network.
                    println("clipsync: ${peer.pairedLogLine(via = "wire")}")
                    peer.deviceId
                }
            },
            fileEngine = fileEngine,
            mirror = mirrorEngine,
            // Fresh per link, so peers track this Mac across network changes; stored rows
            // refresh from the peer's Hello the same way (stale-endpoint fix, 2026-08-12).
            myEndpoints = { localAddresses().map { addr -> "$addr:$SYNC_PORT" } },
            endpointSink = { id, eps ->
                peerStore.updateAddresses(id, eps)
                println("clipsync: endpoints refreshed for $id -> $eps")
            },
        )
        manager.startServer(SYNC_PORT, host = "0.0.0.0")
        // Symmetric P2P: also dial known peers (whichever side connects first wins; the
        // manager dedups the duplicate link). Backoff keeps an offline peer cheap.
        PeerDialer(manager, peerStore, appScope).start()
        // mDNS: advertise self and dial paired peers the moment they appear on the LAN.
        runCatching {
            JmDnsDiscovery().start(identity.deviceId, SYNC_PORT) { found ->
                val peer = peerStore.get(found.deviceId) ?: return@start
                if (manager.isConnected(found.deviceId)) return@start // JmDNS re-resolves periodically
                // Logged so an on-device run can attribute a connect to mDNS vs. the dialer.
                println("clipsync: mDNS discovered ${found.deviceId} at ${found.host}:${found.port}; dialing")
                appScope.launch {
                    manager.dialPeer(found.deviceId, listOf("${found.host}:${found.port}"), peer.certFingerprint)
                }
            }
        }.onFailure { println("clipsync: mDNS unavailable: ${it.message}") }
        // File-based pairing bootstrap kept for headless testing (QR is the primary path).
        File(File(System.getProperty("user.home"), ".clipsync").apply { mkdirs() }, "my-payload.txt").writeText(myPayload)
        watchPeerPayload(appScope, pairing)
        watchSendFile(appScope, fileEngine)
        println("clipsync: identity ${identity.deviceId}, TLS fp ${tls.fingerprint}, server :$SYNC_PORT")
        Boot(
            engine, fileEngine, identity.deviceName, manager.connectedPeers, myPayload,
            mirrorEngine, phoneNotifs, notifPings, smsThreads, smsMessages,
            fsRoots, fsEntries, mediaItems, thumbs, fsResults, fsEpoch, mediaEpoch,
        )
    }

    // Capture: record locally for history and hand to the engine to broadcast.
    appScope.launch {
        ClipboardWatcher(MacPasteboard()).changes().collect { clip ->
            val now = System.currentTimeMillis()
            // Record locally only for a genuine capture; the engine returns false for an echo
            // of a just-applied remote value, which the remote path already recorded.
            when (clip) {
                is Clip.Text ->
                    if (boot.engine.onLocalCapture(clip.text, now)) repo.record(LOCAL_DEVICE_ID, clip.text, now)
                is Clip.Image ->
                    if (boot.engine.onLocalImageCapture(clip.bytes, clip.mime, now)) {
                        repo.recordImage(LOCAL_DEVICE_ID, clip.mime, clip.bytes.size, now)
                    }
            }
        }
    }

    watchMirrorCmd(appScope, boot)

    application {
        var windowVisible by remember { mutableStateOf(true) }
        val icon = remember { trayIcon() }
        val connected by boot.connectedPeers.collectAsState()
        val trayPeers = remember(connected) { peerStore.all() }
        val status = statusLine(connected, trayPeers)
        val trayState = rememberTrayState()

        // Mirrored phone notifications surface as native macOS notifications.
        LaunchedEffect(Unit) {
            boot.notifPings.collect { n ->
                trayState.sendNotification(
                    androidx.compose.ui.window.Notification("${n.app} — ${n.title}", n.text),
                )
            }
        }

        Tray(
            state = trayState,
            icon = icon,
            tooltip = "clipsync (${boot.deviceName}) — $status",
            onAction = { windowVisible = true },
            menu = {
                Item(status, enabled = false) {}
                Separator()
                Item("Open clipsync") { windowVisible = true }
                Item("Quit clipsync") { exitApplication() }
            },
        )

        if (windowVisible) {
            Window(
                onCloseRequest = { windowVisible = false },
                title = "clipsync — $status",
                state = rememberWindowState(width = 420.dp, height = 720.dp),
            ) {
                // AirDrop moment: any file dropped on the window streams to connected peers.
                DisposableEffect(Unit) {
                    val dropWindow = window
                    DropTarget(
                        dropWindow,
                        object : DropTargetAdapter() {
                            override fun drop(event: DropTargetDropEvent) {
                                event.acceptDrop(DnDConstants.ACTION_COPY)
                                val dropped = runCatching {
                                    (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                                        .orEmpty().filterIsInstance<File>()
                                }.getOrDefault(emptyList())
                                event.dropComplete(true)
                                dropped.forEach { f -> appScope.launch { sendLocalFile(boot.fileEngine, f) } }
                            }
                        },
                    )
                    onDispose { dropWindow.dropTarget = null }
                }
                val pickAndSend = {
                    // Native macOS open dialog; Swing dispatcher because AWT dialogs are modal.
                    appScope.launch(Dispatchers.Swing) {
                        val dialog = FileDialog(window, "Send to paired devices", FileDialog.LOAD)
                        dialog.isMultipleMode = true
                        dialog.isVisible = true
                        dialog.files.orEmpty().forEach { f -> appScope.launch { sendLocalFile(boot.fileEngine, f) } }
                    }
                    Unit
                }
                DesktopScreen(repo, peerStore, connected, boot, pickAndSend)
            }
        }
    }
}

private class Boot(
    val engine: SyncEngine,
    val fileEngine: FileTransferEngine,
    val deviceName: String,
    val connectedPeers: StateFlow<Set<String>>,
    val myPayload: String,
    val mirror: MirrorEngine,
    val phoneNotifs: StateFlow<List<MirrorEvent.NotifPosted>>,
    val notifPings: MutableSharedFlow<MirrorEvent.NotifPosted>,
    val smsThreads: StateFlow<List<SmsThread>>,
    val smsMessages: StateFlow<Map<Long, List<SmsMessage>>>,
    val fsRoots: StateFlow<List<FsRoot>>,
    val fsEntries: StateFlow<MirrorEvent.FsEntries?>,
    val mediaItems: StateFlow<List<MediaItem>>,
    val thumbs: StateFlow<Map<Long, String>>,
    val fsResults: MutableSharedFlow<MirrorEvent.FsResult>,
    val fsEpoch: StateFlow<Int>,
    val mediaEpoch: StateFlow<Int>,
)

/** Streams [file] to every connected peer; logs instead of throwing (UI shows the state). */
private suspend fun sendLocalFile(fileEngine: FileTransferEngine, file: File) {
    if (!file.isFile) return
    val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"
    val source = FileSource(file.name, file.length(), mime) { file.inputStream() }
    if (!fileEngine.sendFile(source)) println("clipsync: file send skipped — no peers connected")
}

/** One status line shared by the tray, the window title, and the header chip. */
private fun statusLine(connected: Set<String>, peers: List<Peer>): String {
    val names = peers.filter { it.deviceId in connected }.map { it.deviceName }
    return when {
        names.isNotEmpty() -> "Connected to ${names.joinToString()}"
        peers.isNotEmpty() -> "Waiting for ${peers.joinToString { it.deviceName }}"
        else -> "Not paired yet"
    }
}

/** Window content: device status first, then sending, the tabbed panes, and pairing. */
@Composable
private fun DesktopScreen(
    repo: ClipRepository,
    peerStore: PeerStore,
    connected: Set<String>,
    boot: Boot,
    onPickFiles: () -> Unit,
) {
    MaterialTheme {
        // Recompute the paired list whenever the connected set changes (e.g. a new pairing).
        val peers = remember(connected) { peerStore.all() }
        val labelFor = remember(peers) {
            { id: String ->
                if (id == LOCAL_DEVICE_ID) "This Mac"
                else peers.firstOrNull { it.deviceId == id }?.deviceName ?: id.take(8)
            }
        }
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("clipsync", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StatusChip(connected.isNotEmpty(), statusLine(connected, peers))
            }
            if (peers.isEmpty()) {
                Text("Scan this QR with the phone app to pair:", style = MaterialTheme.typography.labelMedium)
                Image(
                    bitmap = remember(boot.myPayload) { qrImageBitmap(boot.myPayload) },
                    contentDescription = "pairing QR code",
                    modifier = Modifier.size(180.dp),
                )
            } else {
                peers.forEach { p ->
                    PeerRow(p.deviceName, ClipsyncCrypto.shortAuthString(p.perPairKey), p.deviceId in connected)
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPickFiles, enabled = connected.isNotEmpty()) { Text("Send a file…") }
                Column {
                    Text("or drop files on this window", style = MaterialTheme.typography.labelSmall)
                    Text("received → ~/Downloads/clipsync", style = MaterialTheme.typography.labelSmall)
                }
            }
            val transfers by boot.fileEngine.transfers.collectAsState()
            transfers.take(4).forEach { t -> TransferRow(t, labelFor) }
            HorizontalDivider()
            var tab by remember { mutableStateOf("Activity") }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Activity", "Notifications", "Messages", "Files").forEach { name ->
                    TextButton(onClick = { tab = name }) {
                        Text(if (tab == name) "• $name" else name, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    "Notifications" -> NotificationsPane(boot)
                    "Messages" -> MessagesPane(boot)
                    "Files" -> FilesScreen(boot)
                    else -> HistoryScreen(repo, labelFor)
                }
            }
            if (peers.isNotEmpty()) PairMoreFooter(boot.myPayload)
        }
    }
}

/** Mirrored phone notifications, newest first; RemoteInput-capable ones take a reply. */
@Composable
private fun NotificationsPane(boot: Boot) {
    val notifs by boot.phoneNotifs.collectAsState()
    val scope = rememberCoroutineScope()
    if (notifs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No phone notifications yet.\nEnable \"Notification mirroring\" in the phone app.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(notifs.size) { i ->
            val n = notifs[i]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${n.app} — ${n.title}", style = MaterialTheme.typography.titleSmall)
                    Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                    Text(notifTimeFormat.format(Date(n.whenMs)), style = MaterialTheme.typography.labelSmall)
                    if (n.canReply) {
                        var reply by remember(n.key) { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = reply,
                                onValueChange = { reply = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Reply…") },
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    val text = reply
                                    reply = ""
                                    scope.launch { boot.mirror.send(null, MirrorEvent.NotifReply(n.key, text)) }
                                },
                                enabled = reply.isNotBlank(),
                            ) { Text("Send") }
                        }
                    }
                }
            }
        }
    }
}

/** Phone SMS: thread list → one thread → compose. Data arrives via the mirror engine. */
@Composable
private fun MessagesPane(boot: Boot) {
    val threads by boot.smsThreads.collectAsState()
    val messagesByThread by boot.smsMessages.collectAsState()
    val scope = rememberCoroutineScope()
    var openThread by remember { mutableStateOf<SmsThread?>(null) }
    LaunchedEffect(Unit) { boot.mirror.send(null, MirrorEvent.SmsQueryThreads) }

    val thread = openThread
    if (thread == null) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (threads.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No conversations yet.\nGrant \"Messages\" in the phone app, then Refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(threads.size) { i ->
                        val t = threads[i]
                        Card(
                            Modifier.fillMaxWidth().clickable {
                                openThread = t
                                scope.launch { boot.mirror.send(null, MirrorEvent.SmsQueryThread(t.threadId)) }
                            },
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(t.address, style = MaterialTheme.typography.titleSmall)
                                Text(t.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Text(
                                    "${notifTimeFormat.format(Date(t.dateMs))} · ${t.count} recent",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { scope.launch { boot.mirror.send(null, MirrorEvent.SmsQueryThreads) } }) {
                Text("Refresh")
            }
        }
    } else {
        val messages = messagesByThread[thread.threadId].orEmpty()
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openThread = null }) { Text("← Threads") }
                Text(thread.address, style = MaterialTheme.typography.titleSmall)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages.size) { i ->
                    val m = messages[i]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                if (m.outbound) "→ ${m.body}" else m.body,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(notifTimeFormat.format(Date(m.dateMs)), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            var draft by remember(thread.threadId) { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Text ${thread.address}…") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val body = draft
                        draft = ""
                        scope.launch {
                            boot.mirror.send(null, MirrorEvent.SmsSend(thread.address, body))
                            // Refresh shortly after; the phone also pushes on provider changes.
                            kotlinx.coroutines.delay(2500)
                            boot.mirror.send(null, MirrorEvent.SmsQueryThread(thread.threadId))
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

/**
 * Browse the phone (M9). Two views over one protocol: a folder tree and a photo grid.
 * Destructive actions confirm first, and the phone moves deletions to a trash folder, so a
 * mis-click costs a restore rather than a photo.
 */
@Composable
private fun FilesScreen(boot: Boot) {
    val scope = rememberCoroutineScope()
    val roots by boot.fsRoots.collectAsState()
    val listing by boot.fsEntries.collectAsState()
    val photos by boot.mediaItems.collectAsState()
    val thumbs by boot.thumbs.collectAsState()
    val fsEpoch by boot.fsEpoch.collectAsState()
    val mediaEpoch by boot.mediaEpoch.collectAsState()
    // Every browse request targets ONE phone rather than broadcasting (mirror.send(null, …)
    // means every connected peer). A delete or rename broadcast to every paired phone would
    // trash-move whatever sits at that relative path on each of them — Camera/IMG_0001.jpg
    // means different things on different phones. Targeting only the destructive ops would be
    // worse than broadcasting all of them: a listing from one phone and a delete addressed to
    // another. First-connected-peer matches today's de-facto behaviour (there's no picker yet).
    val connected by boot.connectedPeers.collectAsState()
    val peer = connected.firstOrNull()
    var root by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<List<String>>(emptyList()) }
    var renaming by remember { mutableStateOf<String?>(null) }
    // Refusals are kept PER OPERATION and cleared when that operation next succeeds. A single
    // shared variable latches: a failed delete ("trash rejected") would sit there and then be
    // offered as the reason a legitimately empty photo grid is empty — worse than a generic
    // message, because it points the user at the wrong thing entirely.
    var refusals by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) {
        boot.fsResults.collect { r ->
            refusals = if (r.ok) refusals - r.op else refusals + (r.op to r.detail.ifBlank { "refused" })
        }
    }
    // A successful roots/listing/media reply is not an FsResult, so clear those here instead.
    // Cleared on the epoch counters, not the payloads: MediaItems(emptyList()) is a legitimate
    // success that photos.isNotEmpty() would misread as nothing, and FsEntries is a data class,
    // so re-listing an unchanged directory produces a value == the cached one that
    // MutableStateFlow conflates and listing never re-emits.
    LaunchedEffect(roots) { if (roots.isNotEmpty()) refusals = refusals - "roots" }
    LaunchedEffect(fsEpoch) { if (fsEpoch > 0) refusals = refusals - "list" }
    LaunchedEffect(mediaEpoch) { if (mediaEpoch > 0) refusals = refusals - "media" - "thumbs" }
    val browseRefusal = refusals["roots"] ?: refusals["list"]
    val photoRefusal = refusals["media"] ?: refusals["thumbs"]
    val actionRefusal = refusals["delete"] ?: refusals["rename"] ?: refusals["pull"] ?: refusals["push"]

    fun list(r: String, p: String) {
        root = r
        path = p
        scope.launch { boot.mirror.send(peer, MirrorEvent.FsQueryList(r, p)) }
    }

    LaunchedEffect(Unit) { boot.mirror.send(peer, MirrorEvent.FsQueryRoots) }
    LaunchedEffect(roots) { if (root.isEmpty() && roots.isNotEmpty()) list(roots.first().id, "") }
    LaunchedEffect(grid) {
        if (grid) boot.mirror.send(peer, MirrorEvent.MediaQuery(0, 60))
    }
    // Keyed on thumbs as well as photos: a MediaQuery returns up to 60 items but a ThumbQuery
    // carries at most 24, so keying on photos alone would leave items 25+ blank forever. The
    // requested set stops the loop from re-asking for ids the phone cannot produce a thumbnail
    // for — those are omitted from the reply, so `missing` would never drain without it.
    var requestedThumbs by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(photos, thumbs) {
        val missing = photos.map { it.id }
            .filterNot { thumbs.containsKey(it) || it in requestedThumbs }
            .take(24)
        if (missing.isNotEmpty()) {
            requestedThumbs = requestedThumbs + missing
            boot.mirror.send(peer, MirrorEvent.ThumbQuery(missing))
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            roots.forEach { r ->
                TextButton(onClick = { list(r.id, "") }) {
                    Text(r.label, fontWeight = if (r.id == root) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { grid = !grid }) { Text(if (grid) "Files" else "Photos") }
        }
        if (roots.isEmpty()) {
            // Say why, don't guess. The phone distinguishes "browsing disabled" from "photo
            // permission not granted" and sends the reason in FsResult.detail; an empty list
            // cannot tell those apart, and telling someone they have no photos when they
            // actually denied a permission sends them to the wrong settings screen.
            Text(
                browseRefusal?.let { "The phone refused: $it." }
                    ?: "No storage offered yet.\nOn the phone, turn on \"Let a paired Mac browse my files\".",
                Modifier.padding(top = 24.dp),
            )
            return@Column
        }
        if (grid && photos.isEmpty()) {
            // Not covered by the roots-empty branch above: roots are permission-independent, so
            // with browsing on and photos denied the phone still returns roots and only the grid
            // comes back empty. Without this the user sees a blank grid and no reason at all.
            Text(
                photoRefusal?.let { "The phone refused: $it." } ?: "No photos on the phone yet.",
                Modifier.padding(top = 24.dp),
            )
        } else if (grid) {
            LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.weight(1f)) {
                gridItems(photos, key = { it.id }) { item ->
                    Column(Modifier.padding(4.dp)) {
                        // Peer-supplied bytes. Malformed base64, or valid base64 that is not an
                        // image, throws from the decoder — and an uncaught throw inside a grid
                        // item during recomposition takes the window down. remember() is called
                        // unconditionally so composition order stays stable across recompositions.
                        val b64 = thumbs[item.id]
                        val bitmap = remember(b64) {
                            b64?.let {
                                runCatching {
                                    org.jetbrains.skia.Image
                                        .makeFromEncoded(java.util.Base64.getDecoder().decode(it))
                                        .toComposeImageBitmap()
                                }.getOrNull()
                            }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = item.name,
                                modifier = Modifier.size(112.dp),
                            )
                        } else {
                            Box(Modifier.size(112.dp))
                        }
                        Text(item.name, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            if (path.isNotEmpty()) {
                TextButton(onClick = { list(root, path.substringBeforeLast('/', "")) }) { Text("← ${path.ifEmpty { "/" }}") }
            }
            // A refused delete or rename otherwise just closes its dialog, leaving a listing
            // that looks unchanged and no way to tell "it failed" from "it worked invisibly".
            actionRefusal?.let {
                Text("Last action refused: $it.", Modifier.padding(vertical = 4.dp))
            }
            // Only render a listing that matches where we currently are. Replies are dispatched
            // per-request on the phone's IO pool, so a slower reply for a directory we have
            // navigated away from can arrive last — and acting on it would build paths from
            // this path plus that directory's entry names, which for a delete could target a
            // same-named file in the wrong folder.
            val visible = listing?.takeIf { it.root == root && it.path == path }
            if (visible == null) {
                // A refused FsQueryList never produces a new FsEntries, so `visible` stays null
                // forever — without this the pane would sit on "Loading…" indefinitely while the
                // real reason ("browsing disabled") was already captured and had nowhere to go.
                Text(
                    browseRefusal?.let { "The phone refused: $it." } ?: "Loading…",
                    Modifier.padding(top = 24.dp),
                )
            } else if (visible.entries.isEmpty()) {
                Text(
                    browseRefusal?.let { "The phone refused: $it." } ?: "This folder is empty.",
                    Modifier.padding(top = 24.dp),
                )
            } else LazyColumn(Modifier.weight(1f)) {
                items(visible.entries, key = { it.name }) { entry ->
                    val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(if (entry.dir) "📁" else "📄", Modifier.padding(end = 6.dp))
                        Text(
                            entry.name,
                            Modifier.weight(1f).clickable {
                                if (entry.dir) list(root, child)
                                else scope.launch { boot.mirror.send(peer, MirrorEvent.FsPull(root, child)) }
                            },
                        )
                        if (!entry.dir) Text("${entry.size / 1024} KB", Modifier.padding(end = 8.dp))
                        TextButton(onClick = { renaming = child }) { Text("Rename") }
                        TextButton(onClick = { confirm = listOf(child) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (confirm.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirm = emptyList() },
            title = { Text("Move ${confirm.size} item(s) to the phone's trash?") },
            text = { Text(confirm.joinToString("\n").take(400) + "\n\nRecoverable from .clipsync-trash on the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    val paths = confirm
                    confirm = emptyList()
                    scope.launch {
                        boot.mirror.send(peer, MirrorEvent.FsDelete(root, paths))
                        // Await the FsResult before re-listing rather than firing both events
                        // back to back: the phone dispatches each browse event independently on
                        // Dispatchers.IO, so a bare fire-and-forget list races the delete and
                        // usually wins — delete costs many more canonical()/Binder round trips —
                        // and the pane renders the pre-delete state with no second refresh ever
                        // coming. A timeout means a lost reply can't wedge the UI.
                        withTimeoutOrNull(5_000) { boot.fsResults.first { it.op == "delete" } }
                        boot.mirror.send(peer, MirrorEvent.FsQueryList(root, path))
                    }
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirm = emptyList() }) { Text("Cancel") } },
        )
    }

    renaming?.let { target ->
        var name by remember(target) { mutableStateOf(target.substringAfterLast('/')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch {
                        boot.mirror.send(peer, MirrorEvent.FsRename(root, target, name))
                        // Same race as delete above: await the reply before re-listing.
                        withTimeoutOrNull(5_000) { boot.fsResults.first { it.op == "rename" } }
                        boot.mirror.send(peer, MirrorEvent.FsQueryList(root, path))
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

private val notifTimeFormat = java.text.SimpleDateFormat("HH:mm")

private val ConnectedGreen = Color(0xFF2E7D32)
private val OfflineGray = Color(0xFF8E8E93)

@Composable
private fun StatusDot(on: Boolean) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(if (on) ConnectedGreen else OfflineGray))
}

@Composable
private fun StatusChip(on: Boolean, text: String) {
    val tint = if (on) ConnectedGreen else OfflineGray
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(on)
        Text(text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun PeerRow(name: String, sas: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(on)
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                (if (on) "Connected" else "Not connected — syncs on reconnect") + " · code $sas",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Pairing stays reachable after the first pairing without leading the screen. */
@Composable
private fun PairMoreFooter(myPayload: String) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = !show }) { Text(if (show) "Hide pairing QR" else "Pair another device…") }
    if (show) {
        Image(
            bitmap = remember(myPayload) { qrImageBitmap(myPayload) },
            contentDescription = "pairing QR code",
            modifier = Modifier.size(160.dp),
        )
    }
}

@Composable
private fun TransferRow(t: TransferState, labelFor: (String) -> String) {
    val direction = if (t.outbound) "→ ${labelFor(t.peerDeviceId)}" else "← ${labelFor(t.peerDeviceId)}"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val detail = when (t.status) {
            TransferState.Status.ACTIVE -> "${formatBytes(t.transferredBytes)} / ${formatBytes(t.sizeBytes)}"
            TransferState.Status.DONE -> if (t.outbound) "sent" else t.detail ?: "received"
            TransferState.Status.FAILED -> "failed: ${t.detail}"
        }
        Text("$direction  ${t.name} — $detail", style = MaterialTheme.typography.labelSmall, maxLines = 2)
        if (t.status == TransferState.Status.ACTIVE && t.sizeBytes > 0) {
            val fraction = (t.transferredBytes.toFloat() / t.sizeBytes).coerceIn(0f, 1f)
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(OfflineGray.copy(alpha = 0.25f)),
            ) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(ConnectedGreen))
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / (1 shl 30).toDouble())
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1 shl 20).toDouble())
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / (1 shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * Polls ~/.clipsync/send-file.txt: writing an absolute path there streams that file to the
 * connected peers, and the file is deleted once consumed. Headless-harness hook in the
 * peer-payload.txt idiom — drag-and-drop and the picker are the real UI; an on-device run
 * needs a scriptable send with assertable logs.
 */
private fun watchSendFile(scope: CoroutineScope, fileEngine: FileTransferEngine) {
    val file = File(File(System.getProperty("user.home"), ".clipsync"), "send-file.txt")
    scope.launch {
        var last: String? = null
        while (true) {
            delay(1000)
            val text = runCatching { if (file.exists()) file.readText().trim() else null }.getOrNull()
            if (!text.isNullOrEmpty() && text != last) {
                last = text
                // Consume before acting: a restart used to re-send whatever was last queued,
                // because the dedup var resets with the process.
                runCatching { file.delete() }
                val f = File(text)
                if (f.isFile) {
                    println("clipsync: send-file start path=$text size=${f.length()}")
                    sendLocalFile(fileEngine, f)
                } else {
                    println("clipsync: send-file not a file: $text")
                }
            }
        }
    }
}

/**
 * Polls ~/.clipsync/mirror-cmd.txt (consumed once read): harness hook for the mirror paths.
 * Lines: "sms-threads" | "sms-thread <id>" | "sms-send <to> <body…>" | "notif-reply <text…>"
 * — notif-reply targets the newest reply-capable notification. The tabs are the real UI.
 */
private fun watchMirrorCmd(scope: CoroutineScope, boot: Boot) {
    val file = File(File(System.getProperty("user.home"), ".clipsync"), "mirror-cmd.txt")
    scope.launch {
        while (true) {
            delay(1000)
            val text = runCatching { if (file.exists()) file.readText().trim() else null }.getOrNull()
            if (text.isNullOrEmpty()) continue
            runCatching { file.delete() }
            for (line in text.lines()) {
                val parts = line.trim().split(" ", limit = 3)
                val event = when (parts[0]) {
                    "sms-threads" -> MirrorEvent.SmsQueryThreads
                    "sms-thread" -> parts.getOrNull(1)?.toLongOrNull()?.let { MirrorEvent.SmsQueryThread(it) }
                    "sms-send" -> if (parts.size == 3) MirrorEvent.SmsSend(parts[1], parts[2]) else null
                    "notif-reply" -> boot.phoneNotifs.value.firstOrNull { it.canReply }
                        ?.let { MirrorEvent.NotifReply(it.key, line.removePrefix("notif-reply").trim()) }
                    "fs-roots" -> MirrorEvent.FsQueryRoots
                    "fs-list" -> if (parts.size >= 2) MirrorEvent.FsQueryList(parts[1], parts.getOrElse(2) { "" }) else null
                    "fs-pull" -> if (parts.size == 3) MirrorEvent.FsPull(parts[1], parts[2]) else null
                    "fs-push" -> if (parts.size == 3) MirrorEvent.FsPush(parts[1], parts[2]) else null
                    "fs-delete" -> if (parts.size == 3) MirrorEvent.FsDelete(parts[1], listOf(parts[2])) else null
                    "fs-rename" -> if (parts.size == 3 && parts[2].contains(' ')) {
                        // The outer split above is limit=3 (verb, root, rest-of-line), matching
                        // sms-send's "last field is free text" idiom, so "path newName" arrives
                        // fused in parts[2] — parts.size can never reach 4, so the literal
                        // parts[3] read this verb would otherwise need is unreachable. Re-split
                        // the fused field instead of widening the outer limit, which would break
                        // sms-send's multi-word body. Split from the RIGHT: path (an existing
                        // /sdcard file, often with spaces in its real name) keeps everything up
                        // to the last space; newName is the final token, since the operator
                        // typing it can trivially choose one with no space.
                        MirrorEvent.FsRename(parts[1], parts[2].substringBeforeLast(' '), parts[2].substringAfterLast(' '))
                    } else null
                    "media" -> MirrorEvent.MediaQuery(0, 20)
                    else -> null
                }
                if (event == null) {
                    println("clipsync: mirror-cmd unrecognized: $line")
                    continue
                }
                println("clipsync: mirror-cmd ${parts[0]} sent=${boot.mirror.send(null, event)}")
            }
        }
    }
}

/** Polls ~/.clipsync/peer-payload.txt and pairs whenever its contents change. */
private fun watchPeerPayload(scope: CoroutineScope, pairing: PairingManager) {
    val file = File(File(System.getProperty("user.home"), ".clipsync"), "peer-payload.txt")
    scope.launch {
        var lastPaired: String? = null
        while (true) {
            delay(1000)
            val text = runCatching { if (file.exists()) file.readText().trim() else null }.getOrNull()
            if (!text.isNullOrEmpty() && text != lastPaired) {
                val peer = pairing.pair(text, System.currentTimeMillis())
                if (peer != null) {
                    lastPaired = text
                    // via=file: this is the out-of-band bootstrap, NOT reciprocal pairing over
                    // the wire. The harness relies on the two being distinguishable.
                    println("clipsync: ${peer.pairedLogLine(via = "file")}")
                } else {
                    println("clipsync: peer-payload.txt is not a valid payload")
                }
            }
        }
    }
}
