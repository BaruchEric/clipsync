package ca.beric.clipsync.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.core.ClipboardWatcher
import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import ca.beric.clipsync.core.MacPasteboard
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.db.DriverFactory
import ca.beric.clipsync.identity.DeviceIdentity
import ca.beric.clipsync.identity.SecretStore
import ca.beric.clipsync.pairing.PairingManager
import ca.beric.clipsync.pairing.PeerStore
import ca.beric.clipsync.sync.DesktopClipboardApplier
import ca.beric.clipsync.sync.SyncEngine
import ca.beric.clipsync.transport.ConnectionManager
import ca.beric.clipsync.transport.TlsIdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    val (engine, identityName) = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val secretStore = SecretStore()
        val identity = DeviceIdentity(db, secretStore).getOrCreate("Mac")
        val appSupportDir = File(System.getProperty("user.home"), "Library/Application Support/clipsync")
        val tls = TlsIdentityStore(File(appSupportDir, "tls.p12"), secretStore).loadOrCreate("clipsync-mac")
        val engine = SyncEngine(identity.deviceId, repo, DesktopClipboardApplier())
        val manager = ConnectionManager(
            localDeviceId = identity.deviceId,
            tlsIdentity = tls,
            engine = engine,
            perPairKeyFor = { peerStore.get(it)?.perPairKey },
            scope = appScope,
        )
        manager.startServer(SYNC_PORT, host = "0.0.0.0")
        val pairing = PairingManager(identity, peerStore)
        writePairingFiles(pairing, tls.fingerprint, SYNC_PORT)
        watchPeerPayload(appScope, pairing)
        println("clipsync: identity ${identity.deviceId}, TLS fp ${tls.fingerprint}, server :$SYNC_PORT")
        engine to identity.deviceName
    }

    // Capture: record locally for history and hand to the engine to broadcast.
    appScope.launch {
        ClipboardWatcher(MacPasteboard()).changes().collect { text ->
            val now = System.currentTimeMillis()
            repo.record(LOCAL_DEVICE_ID, text, now)
            engine.onLocalCapture(text, now)
        }
    }

    application {
        var windowVisible by remember { mutableStateOf(true) }
        val icon = remember { trayIcon() }

        Tray(
            icon = icon,
            tooltip = "clipsync ($identityName)",
            onAction = { windowVisible = true },
            menu = {
                Item("Open history") { windowVisible = true }
                Separator()
                Item("Quit clipsync") { exitApplication() }
            },
        )

        if (windowVisible) {
            Window(
                onCloseRequest = { windowVisible = false },
                title = "clipsync history",
                state = rememberWindowState(width = 380.dp, height = 520.dp),
            ) {
                HistoryScreen(repo)
            }
        }
    }
}

/** Writes this device's pairing payload for out-of-band exchange during the sim. */
private fun writePairingFiles(pairing: PairingManager, fingerprint: String, port: Int) {
    val dir = File(System.getProperty("user.home"), ".clipsync").apply { mkdirs() }
    val payload = pairing.myPayload(fingerprint, localAddresses(), port)
    File(dir, "my-payload.txt").writeText(payload)
    println("clipsync: wrote pairing payload to ${File(dir, "my-payload.txt").absolutePath}")
    println("clipsync payload: $payload")
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
