package ca.beric.clipsync.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import ca.beric.clipsync.sync.DesktopClipboardApplier
import ca.beric.clipsync.sync.SyncEngine
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
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

private const val SYNC_PORT = 47653

private fun trayIcon(): BitmapPainter {
    val bitmap = ImageBitmap(64, 64)
    val paint = Paint().apply { color = Color(0xFF2AA198) }
    Canvas(bitmap).drawRect(0f, 0f, 64f, 64f, paint)
    return BitmapPainter(bitmap)
}

/** Non-loopback IPv4 addresses (LAN + tailnet 100.x) to advertise as dial hints. */
private fun localAddresses(): List<String> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }

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
        val manager = ConnectionManager(
            localDeviceId = identity.deviceId,
            tlsIdentity = tls,
            engine = engine,
            perPairKeyFor = { peerStore.get(it)?.perPairKey },
            scope = appScope,
            myPayload = { myPayload },
            // A phone that scanned this QR sends its payload back; pair it over the wire.
            pairingSink = { json -> pairing.pair(json, System.currentTimeMillis())?.deviceId },
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
        println("clipsync: identity ${identity.deviceId}, TLS fp ${tls.fingerprint}, server :$SYNC_PORT")
        Boot(engine, identity.deviceName, manager.connectedPeers, myPayload)
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
                state = rememberWindowState(width = 420.dp, height = 680.dp),
            ) {
                DesktopScreen(repo, boot.myPayload, peerStore, connected)
            }
        }
    }
}

private class Boot(
    val engine: SyncEngine,
    val deviceName: String,
    val connectedPeers: StateFlow<Set<String>>,
    val myPayload: String,
)

/** Window content: pairing QR + short-auth-string list, above the clipboard history. */
@Composable
private fun DesktopScreen(
    repo: ClipRepository,
    myPayload: String,
    peerStore: PeerStore,
    connected: Set<String>,
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
            Box(Modifier.weight(1f)) { HistoryScreen(repo) }
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
                    println("clipsync: paired ${peer.deviceId} (${peer.deviceName}) endpoints=${peer.addresses}")
                } else {
                    println("clipsync: peer-payload.txt is not a valid payload")
                }
            }
        }
    }
}
