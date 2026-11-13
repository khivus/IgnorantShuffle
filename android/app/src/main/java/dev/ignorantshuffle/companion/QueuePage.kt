package dev.ignorantshuffle.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.animation.core.spring
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun QueuePage(m: AimpController) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val entries by rememberUpdatedState(m.appQueue)
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("App queue", style = MaterialTheme.typography.titleLarge)
            Text("Add tracks to the beginning or end from Playlist. Drag the left grip, or hold a track to reorder. Entries play before weighted choices; AIMP's own queue is unchanged.")
            Text("Automatic handoffs require weighted shuffle and AIMP Shuffle. In manual mode, use Play next.", style = MaterialTheme.typography.bodySmall)
            Row {
                ActionButton(onClick = m::smartNext, enabled = m.connected && !m.pending && m.preview.queueEntryId != null) { Text("Play next") }
                TextButton(onClick = m::clearQueue, enabled = !m.pending && entries.isNotEmpty()) { Text("Clear queue") }
            }
        }
        if (entries.isEmpty()) item { Text("Queue is empty. Open Playlist and tap Add to beginning or Add to end.") }
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            var height by remember { mutableIntStateOf(1) }
            fun dragModifier(immediate: Boolean) = Modifier.pointerInput(entry.id, m.pending) {
                if (!m.pending) {
                    val start: (Offset) -> Unit = { dragging = entry.id; offset = 0f }
                    val finish: () -> Unit = { dragging = null; offset = 0f }
                    val move: (PointerInputChange, Offset) -> Unit = { change, amount ->
                        change.consume(); offset += amount.y
                        val i = entries.indexOfFirst { it.id == entry.id }
                        val direction = if (offset > 0) 1 else -1
                        if (i >= 0 && i + direction in entries.indices && kotlin.math.abs(offset) > height / 2f) {
                            m.moveQueue(i, direction); offset -= direction * height
                        }
                        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }
                        if (item != null && (item.offset + offset < state.layoutInfo.viewportStartOffset + 60 ||
                            item.offset + item.size + offset > state.layoutInfo.viewportEndOffset - 60)) scope.launch { state.scrollBy(amount.y) }
                    }
                    if (immediate) detectDragGestures(onDragStart = start, onDragEnd = finish, onDragCancel = finish, onDrag = move)
                    else detectDragGesturesAfterLongPress(onDragStart = start, onDragEnd = finish, onDragCancel = finish, onDrag = move)
                }
            }
            OutlinedCard(Modifier.fillMaxWidth().animateItem(placementSpec = if(dragging == entry.id) null else spring()).zIndex(if(dragging == entry.id) 1f else 0f).onSizeChanged { height = it.height }
                .graphicsLayer { translationY = if (dragging == entry.id) offset else 0f }.then(dragModifier(false))) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(48.dp).fillMaxHeight().heightIn(min = 48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .semantics {
                            contentDescription = "Drag to reorder ${entry.title}"
                            customActions = listOf(
                                CustomAccessibilityAction("Move earlier") { val i = entries.indexOfFirst { it.id == entry.id }; if (!m.pending && i > 0) { m.moveQueue(i, -1); true } else false },
                                CustomAccessibilityAction("Move later") { val i = entries.indexOfFirst { it.id == entry.id }; if (!m.pending && i >= 0 && i < entries.lastIndex) { m.moveQueue(i, 1); true } else false })
                        }.then(dragModifier(true)), contentAlignment = Alignment.Center) {
                        Text("≡", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f).padding(12.dp)) {
                        Text("[${index + 1}] ${entry.title}", style = MaterialTheme.typography.titleMedium)
                        Text(entry.artist)
                        if (m.tracks.none { it.key == entry.key }) Text("Unavailable in this AIMP session; retained for later.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { m.removeQueue(entry.id) }, enabled = !m.pending) { Text("Remove") }
                    }
                }
            }
        }
    }
}
