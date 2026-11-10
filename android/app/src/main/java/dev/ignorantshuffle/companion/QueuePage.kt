package dev.ignorantshuffle.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun QueuePage(m: AimpController) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("App queue", style = MaterialTheme.typography.titleLarge)
            Text("Add tracks from Playlist. Available entries play in order before weighted choices. Explicit queue choices can include zero-weight tracks. AIMP's own queue is unchanged.", style = MaterialTheme.typography.bodySmall)
            Text("Automatic handoffs require weighted shuffle and AIMP Shuffle. In manual mode, use Play next.", style = MaterialTheme.typography.bodySmall)
            Row {
                ActionButton(onClick = m::smartNext, enabled = m.connected && !m.pending && m.preview.queueEntryId != null) { Text("Play next") }
                TextButton(onClick = m::clearQueue, enabled = !m.pending) { Text("Clear queue") }
            }
        }
        if (m.appQueue.isEmpty()) item { Text("Queue is empty. Open Playlist and tap Add to queue.") }
        itemsIndexed(m.appQueue, key = { _, entry -> entry.id }) { index, entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${index + 1}. ${entry.title}", style = MaterialTheme.typography.titleMedium)
                    Text(entry.artist)
                    if (m.tracks.none { it.key == entry.key }) Text("Unavailable in this AIMP session; retained for later.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { m.moveQueue(index, -1) }, enabled = index > 0 && !m.pending) { Text("Up") }
                        TextButton(onClick = { m.moveQueue(index, 1) }, enabled = index < m.appQueue.lastIndex && !m.pending) { Text("Down") }
                        TextButton(onClick = { m.removeQueue(entry.id) }, enabled = !m.pending) { Text("Remove") }
                    }
                }
            }
        }
    }
}
