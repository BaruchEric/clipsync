package ca.beric.clipsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.beric.clipsync.core.ClipEntry
import ca.beric.clipsync.core.ClipRepository
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun HistoryScreen(repo: ClipRepository) {
    val entries by repo.observeHistory().collectAsState(initial = emptyList())
    MaterialTheme {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Copy something — it will show up here.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = ClipEntry::id) { entry -> HistoryRow(entry) }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss")

@Composable
private fun HistoryRow(entry: ClipEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(
                entry.content.take(200),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
            )
            Text(
                "${entry.deviceId} · ${timeFormat.format(Date(entry.createdAtMs))}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
