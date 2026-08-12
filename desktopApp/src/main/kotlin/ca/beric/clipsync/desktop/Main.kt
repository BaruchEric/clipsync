package ca.beric.clipsync.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
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
import ca.beric.clipsync.pairing.PairingManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
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
        )
        manager.startServer(SYNC_PORT, host = "0.0.0.0")
        // Symmetric P2P: also dial known peers (whichever side connects first wins; the
        // manager dedups the duplicate link). Backoff keeps an offline peer cheap.
        PeerDialer(manager, peerStore, appScope).start()
        // mDNS: advertise self and dial paired peers the moment they appear on the LAN.
        runCatching {
            JmDnsDiscovery().start(identity.deviceId, SYNC_PORT) { found ->
                val peer = peerStore.get(found.deviceId) ?: return@start
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
        Boot(engine, fileEngine, identity.deviceName, manager.connectedPeers, myPayload)
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

    application {
        var windowVisible by remember { mutableStateOf(true) }
        val icon = remember { trayIcon() }
        val connected by boot.connectedPeers.collectAsState()
        val status = if (connected.isEmpty()) "no peers connected" else "${connected.size} peer(s) connected"

        Tray(
            icon = icon,
            tooltip = "clipsync (${boot.deviceName}) — $status",
            onAction = { windowVisible = true },
            menu = {
                Item("Open history & pairing") { windowVisible = true }
                Separator()
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
                DesktopScreen(repo, boot.myPayload, peerStore, connected, boot.fileEngine, pickAndSend)
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
)

/** Streams [file] to every connected peer; logs instead of throwing (UI shows the state). */
private suspend fun sendLocalFile(fileEngine: FileTransferEngine, file: File) {
    if (!file.isFile) return
    val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull() ?: "application/octet-stream"
    val source = FileSource(file.name, file.length(), mime) { file.inputStream() }
    if (!fileEngine.sendFile(source)) println("clipsync: file send skipped — no peers connected")
}

/** Window content: pairing QR + SAS list, file sending/transfers, then clipboard history. */
@Composable
private fun DesktopScreen(
    repo: ClipRepository,
    myPayload: String,
    peerStore: PeerStore,
    connected: Set<String>,
    fileEngine: FileTransferEngine,
    onPickFiles: () -> Unit,
) {
    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scan to pair a phone", style = MaterialTheme.typography.titleSmall)
            Image(
                bitmap = remember(myPayload) { qrImageBitmap(myPayload) },
                contentDescription = "pairing QR code",
                modifier = Modifier.size(180.dp),
            )
            // Recompute the paired list whenever the connected set changes (e.g. a new pairing).
            val peers = remember(connected) { peerStore.all() }
            if (peers.isNotEmpty()) {
                Text(
                    "Paired devices — these codes must match on both screens. If they don't, remove the peer.",
                    style = MaterialTheme.typography.labelSmall,
                )
                peers.forEach { p ->
                    Text(
                        "${p.deviceName}: ${ClipsyncCrypto.shortAuthString(p.perPairKey)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(onClick = onPickFiles, enabled = connected.isNotEmpty()) { Text("Send a file…") }
                Text(
                    "  or drop files on this window · received files: ~/Downloads/clipsync",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            val transfers by fileEngine.transfers.collectAsState()
            if (transfers.isNotEmpty()) {
                transfers.take(4).forEach { t -> TransferRow(t) }
                HorizontalDivider()
            }
            Box(Modifier.weight(1f)) { HistoryScreen(repo) }
        }
    }
}

@Composable
private fun TransferRow(t: TransferState) {
    val direction = if (t.outbound) "→ ${t.peerDeviceId.take(8)}" else "← ${t.peerDeviceId.take(8)}"
    val detail = when (t.status) {
        TransferState.Status.ACTIVE -> "${formatBytes(t.transferredBytes)} / ${formatBytes(t.sizeBytes)}"
        TransferState.Status.DONE -> if (t.outbound) "sent" else t.detail ?: "received"
        TransferState.Status.FAILED -> "failed: ${t.detail}"
    }
    Text("$direction  ${t.name} — $detail", style = MaterialTheme.typography.labelSmall, maxLines = 2)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / (1 shl 30).toDouble())
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1 shl 20).toDouble())
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / (1 shl 10).toDouble())
    else -> "$bytes B"
}

/**
 * Polls ~/.clipsync/send-file.txt: writing an absolute path there streams that file to the
 * connected peers. Headless-harness hook in the peer-payload.txt idiom — drag-and-drop and
 * the picker are the real UI; an on-device run needs a scriptable send with assertable logs.
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
