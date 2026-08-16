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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.beric.clipsync.core.ClipEntry
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.ui.formatClipTime

@Composable
fun HistoryScreen(repo: ClipRepository, labelFor: (String) -> String = { it }) {
    val entries by remember(repo) { repo.observeHistory() }.collectAsState(initial = emptyList())
    MaterialTheme {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Copy something — it will show up here.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = ClipEntry::id) { entry -> HistoryRow(entry, labelFor) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: ClipEntry, labelFor: (String) -> String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(
                if (entry.kind == "image") "🖼 ${entry.content.removePrefix("image: ")}" else entry.content.take(200),
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
