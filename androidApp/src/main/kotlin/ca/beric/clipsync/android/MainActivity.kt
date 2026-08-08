package ca.beric.clipsync.android

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.beric.clipsync.R
import ca.beric.clipsync.core.ClipEntry

class MainActivity : ComponentActivity() {

    private val refreshTick = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        refreshTick.intValue += 1
    }

    private fun accessibilityEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true

    private fun notificationsEnabled(): Boolean =
        getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        setContent {
            val tick by refreshTick
            val showDisclosure = remember { mutableStateOf(false) }
            val entries by AppGraph.repo.observeHistory().collectAsState(initial = emptyList())
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("clipsync", style = MaterialTheme.typography.headlineSmall)

                    // key(tick) re-evaluates grant states after returning from Settings
                    key(tick) {
                        if (!accessibilityEnabled()) {
                            if (!showDisclosure.value) {
                                StatusCard(
                                    "Clipboard capture is off",
                                    "Grant the accessibility permission to capture copies system-wide.",
                                    "Learn more",
                                ) { showDisclosure.value = true }
                            } else {
                                DisclosureCard {
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }
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
                Text("Open accessibility settings")
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
