package ca.beric.clipsync.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import ca.beric.clipsync.BuildConfig
import ca.beric.clipsync.R
import ca.beric.clipsync.android.browse.BrowsePrefs
import ca.beric.clipsync.android.browse.MediaIndex
import ca.beric.clipsync.android.capture.NotifMirrorService
import ca.beric.clipsync.android.capture.SyncForegroundService
import ca.beric.clipsync.android.capture.TestReplyReceiver
import ca.beric.clipsync.core.ClipEntry
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.transfer.TransferState
import ca.beric.clipsync.ui.deviceLabeler
import ca.beric.clipsync.ui.formatClipTime
import ca.beric.clipsync.ui.peerStatusLine
import ca.beric.clipsync.ui.statusLine
import ca.beric.clipsync.ui.transferDetail
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val refreshTick = mutableIntStateOf(0)

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refreshTick.intValue += 1 }

    // ZXing scanner: reads a peer's QR (its pairing payload) and pairs from it.
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val payload = result.contents ?: return@registerForActivityResult
        AppGraph.scope.launch {
            val ok = AppGraph.pairFromScan(payload)
            Log.i("clipsyncScan", "scan-pair ok=$ok")
            refreshTick.intValue += 1
        }
    }

    private fun launchScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the QR shown in clipsync on your other device")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        AppGraph.startSync(this)
        SyncForegroundService.start(this)
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        handlePairingIntent(intent)
        handleShareIntent(intent)
        handleSendPathIntent(intent)
        setContent { Screen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
        handleShareIntent(intent)
        handleSendPathIntent(intent)
    }

    /**
     * adb harness hook (share-sheet is the real path):
     *   adb shell am start -n ca.beric.clipsync/.MainActivity --es send_file_path <app-readable path>
     *
     * Debug builds only: this activity is exported, so on a release build these extras would
     * let ANY installed app read/write the clipboard and exfiltrate files with a bare
     * startActivity. The harness always drives the debug APK, so gating costs it nothing.
     */
    private fun handleSendPathIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        intent?.getStringExtra("send_file_path")?.let { path ->
            AppGraph.scope.launch {
                val ok = AppGraph.sendLocalFile(path)
                Log.i("clipsyncShare", "send-from-intent path=$path ok=$ok")
            }
        }
        intent?.getStringExtra("set_image_clip_uri")?.let { uri ->
            AppGraph.scope.launch {
                val ok = AppGraph.setImageClip(uri)
                Log.i("clipsyncShare", "set-image-clip uri=$uri ok=$ok")
            }
        }
        intent?.getStringExtra("set_text_clip")?.let { text ->
            AppGraph.scope.launch {
                val ok = AppGraph.setTextClip(text)
                Log.i("clipsyncShare", "set-text-clip len=${text.length} ok=$ok")
            }
        }
        if (intent?.hasExtra("debug_read_clip") == true) {
            AppGraph.scope.launch { Log.i("clipsyncShare", "debug-read ${AppGraph.debugReadClip()}") }
        }
        if (intent?.hasExtra("post_test_notification") == true) {
            postTestNotification()
        }
    }

    /** Share-sheet entry: stream the shared content to connected peers ("send to my Mac"). */
    private fun handleShareIntent(intent: Intent?) {
        @Suppress("DEPRECATION") // typed accessors need core 1.10+; the raw ones work everywhere
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        intent?.action = null // consumed: don't re-send on a configuration-change redelivery
        Toast.makeText(this, "clipsync: sending ${uris.size} file(s)…", Toast.LENGTH_SHORT).show()
        val appContext = applicationContext
        AppGraph.scope.launch {
            val sent = AppGraph.sendSharedFiles(appContext, uris)
            if (sent == 0) {
                launch(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        "clipsync: nothing sent — is a paired device connected?",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /**
     * Accepts a peer's pairing payload injected via
     *   adb shell am start -n ca.beric.clipsync/.MainActivity --es pairing_payload_b64 <base64>
     * Base64 avoids the double shell-quoting hazard of passing raw JSON through adb.
     *
     * Debug builds only, same as [handleSendPathIntent]: on a release build this would let
     * any installed app silently pair its own peer (persist it + derive a key) with no
     * confirmation UI. QR scan and the share sheet are the real paths.
     */
    private fun handlePairingIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val payload = intent?.let { readPayloadExtra(it) } ?: return
        AppGraph.scope.launch {
            val ok = AppGraph.pair(payload)
            Log.i("clipsyncPair", "pair-from-intent ok=$ok")
            refreshTick.intValue += 1
        }
    }

    private fun readPayloadExtra(intent: Intent): String? {
        intent.getStringExtra("pairing_payload_b64")?.let { b64 ->
            return runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrNull()
        }
        return intent.getStringExtra("pairing_payload")
    }

    override fun onResume() {
        super.onResume()
        refreshTick.intValue += 1
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    private fun shizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun shizukuGranted(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private fun requestShizuku() {
        runCatching {
            if (Shizuku.shouldShowRequestPermissionRationale()) return@runCatching
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        }
    }

    private fun notificationsEnabled(): Boolean =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun notifMirrorEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun smsGranted(): Boolean =
        checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_REQUEST_CODE) {
            AppGraph.onSmsPermissionsChanged()
            refreshTick.intValue += 1
        }
    }

    /** Harness hook: a notification with a RemoteInput action, for verifying desktop replies. */
    private fun postTestNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("clipsync-test", "clipsync reply test", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val replyIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, TestReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // RemoteInput requires mutable
        )
        val action = Notification.Action.Builder(null, "Reply", replyIntent)
            .addRemoteInput(RemoteInput.Builder(TestReplyReceiver.RESULT_KEY).setLabel("Reply").build())
            .build()
        val notif = Notification.Builder(this, "clipsync-test")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("clipsync reply test")
            .setContentText("Reply to this from the Mac")
            .addAction(action)
            .build()
        manager.notify(NotifMirrorService.TEST_TAG, 4242, notif)
        Log.i("clipsyncNotif", "test notification posted")
    }

    @Composable
    private fun Screen() {
        val tick by refreshTick
        val entries by remember { AppGraph.repo.observeHistory() }.collectAsState(initial = emptyList())
        MaterialTheme {
            Column(
                // safeDrawingPadding: targetSdk 36 is edge-to-edge, so without it the header
                // renders under the status bar.
                Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val connected by AppGraph.connectedPeers.collectAsState()
                val peers = remember(connected, tick) { AppGraph.peerStore.all() }
                val labelFor = remember(peers) { deviceLabeler("This phone", peers) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "clipsync",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(connected.isNotEmpty(), statusLine(connected, peers))
                }
                peers.forEach { p ->
                    PeerRow(p.deviceName, ClipsyncCrypto.shortAuthString(p.perPairKey), p.deviceId in connected)
                }

                Button(onClick = { launchScan() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (peers.isEmpty()) "Scan your computer's QR to pair" else "Scan a device's QR to pair")
                }

                val transfers by AppGraph.transfers.collectAsState()
                transfers.take(3).forEach { t -> TransferRow(t, labelFor) }

                key(tick) {
                    when {
                        !shizukuAvailable() -> StatusCard(
                            "Shizuku not running",
                            "Install Shizuku and start it (wireless debugging or root). clipsync reads the clipboard through it.",
                            "What's this?",
                        ) { refreshTick.intValue += 1 }
                        !shizukuGranted() -> DisclosureCard { requestShizuku() }
                    }
                    if (!notificationsEnabled()) {
                        StatusCard(
                            "Notifications are off",
                            "The sync engine shows a persistent notification while running.",
                            "Enable",
                        ) {
                            startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                            )
                        }
                    }
                    if (!batteryExempt()) {
                        StatusCard(
                            "Battery optimization is on",
                            "Exempting clipsync helps capture survive Doze.",
                            "Exempt",
                        ) {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:$packageName"),
                                ),
                            )
                        }
                    }
                    if (!notifMirrorEnabled()) {
                        StatusCard(
                            "Notification mirroring is off",
                            "See and reply to phone notifications on your computer (opt-in).",
                            "Enable",
                        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    }
                    if (!smsGranted()) {
                        StatusCard(
                            "Messages on your computer is off",
                            "Read and send texts from the desktop app (opt-in).",
                            "Grant",
                        ) {
                            requestPermissions(
                                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS),
                                SMS_REQUEST_CODE,
                            )
                        }
                    }
                    BrowseCard(this@MainActivity)
                }

                Text("Activity", style = MaterialTheme.typography.titleSmall)
                HistoryList(entries, labelFor)
            }
        }
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 4001
        private const val SMS_REQUEST_CODE = 4002
    }
}

@Composable
private fun StatusCard(title: String, body: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.padding(4.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

/**
 * M9 consent: file browsing is off until this is switched on. Turning it on also asks for the
 * read-only media permission the photo grid needs — one tap, both grants.
 */
@Composable
private fun BrowseCard(activity: ComponentActivity) {
    var on by remember { mutableStateOf(BrowsePrefs.enabled(activity)) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    // Consent copy. Name the write capability explicitly: FsPush lets a paired Mac add
    // files to this phone with no per-push confirmation, and "copy" alone reads as pulling
    // files off. The off-state text is what someone reads *before* granting, so it carries
    // the full disclosure. Avoid claiming trashed items "can be restored" — they are moved
    // rather than erased, but no restore UI exists.
    StatusCard(
        "Let a paired Mac browse my files",
        if (on) {
            "On. A paired Mac can browse this phone's files and photos, copy them off, add new " +
                "ones, rename, and trash. Deleted items move to .clipsync-trash rather than " +
                "being erased."
        } else {
            "Off. Turn this on to let a paired Mac browse this phone's photos and files — and " +
                "copy, add, rename, or trash them."
        },
        if (on) "Turn off" else "Turn on",
    ) {
        val next = !on
        BrowsePrefs.setEnabled(activity, next)
        on = next
        if (next) askMedia.launch(MediaIndex.PERMISSIONS)
        // Both directions, deliberately outside the `if`. Turning browsing ON binds the
        // SHELL-uid bridge now, because it is only bound while browsing is on and the first
        // browse would otherwise fail until the app was relaunched. Turning it OFF stops that
        // privileged helper instead of leaving it running with nothing to answer.
        AppGraph.ensureFileBridgeBound(activity)
    }
}

@Composable
private fun DisclosureCard(onProceed: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.disclosure_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.disclosure_body), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onProceed, modifier = Modifier.fillMaxWidth()) {
                Text("Grant clipsync access via Shizuku")
            }
        }
    }
}

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
            Text(peerStatusLine(on, sas), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TransferRow(t: TransferState, labelFor: (String) -> String) {
    val direction = if (t.outbound) "→ ${labelFor(t.peerDeviceId)}" else "← ${labelFor(t.peerDeviceId)}"
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$direction  ${t.name} — ${transferDetail(t)}", style = MaterialTheme.typography.labelSmall, maxLines = 2)
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

@Composable
private fun ColumnScope.HistoryList(entries: List<ClipEntry>, labelFor: (String) -> String) {
    if (entries.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("History is empty. Copy something once capture is enabled.")
        }
    } else {
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = ClipEntry::id) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            if (entry.kind == "image") "🖼 ${entry.content.removePrefix("image: ")}"
                            else entry.content.take(200),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                        )
                        Text(
                            "${labelFor(entry.deviceId)} · ${formatClipTime(entry.createdAtMs)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
