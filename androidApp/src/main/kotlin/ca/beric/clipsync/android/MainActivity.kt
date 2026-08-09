package ca.beric.clipsync.android

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.beric.clipsync.R
import ca.beric.clipsync.android.capture.SyncForegroundService
import ca.beric.clipsync.core.ClipEntry
import ca.beric.clipsync.crypto.ClipsyncCrypto
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
        setContent { Screen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    /**
     * Accepts a peer's pairing payload injected via
     *   adb shell am start -n ca.beric.clipsync/.MainActivity --es pairing_payload_b64 <base64>
     * Base64 avoids the double shell-quoting hazard of passing raw JSON through adb.
     */
    private fun handlePairingIntent(intent: Intent?) {
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

    @Composable
    private fun Screen() {
        val tick by refreshTick
        val entries by AppGraph.repo.observeHistory().collectAsState(initial = emptyList())
        MaterialTheme {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("clipsync", style = MaterialTheme.typography.headlineSmall)
                val connected by AppGraph.connectedPeers.collectAsState()
                Text(
                    if (connected.isEmpty()) "No peers connected" else "${connected.size} peer(s) connected",
                    style = MaterialTheme.typography.labelMedium,
                )

                Button(onClick = { launchScan() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan a device's QR to pair")
                }

                val peers = remember(connected, tick) { AppGraph.peerStore.all() }
                if (peers.isNotEmpty()) {
                    Text(
                        "Paired — these codes must match on both screens. If they don't, remove the peer.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    peers.forEach { p ->
                        Text(
                            "${p.deviceName}: ${ClipsyncCrypto.shortAuthString(p.perPairKey)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

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
                }

                HistoryList(entries)
            }
        }
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 4001
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

@Composable
private fun ColumnScope.HistoryList(entries: List<ClipEntry>) {
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
                        Text(entry.content.take(200), style = MaterialTheme.typography.bodyMedium)
                        Text(entry.deviceId, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
