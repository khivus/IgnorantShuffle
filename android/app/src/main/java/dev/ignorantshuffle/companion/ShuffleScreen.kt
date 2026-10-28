package dev.ignorantshuffle.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

private val pages = listOf("Player", "Tracks", "Shuffle", "Alerts", "Theme", "Logs")

@Composable
internal fun ShuffleScreen(m: AimpController, settings: () -> Unit, openAimp: () -> Unit, copy: () -> Unit,
    exportLog: () -> Unit, exportWeights: () -> Unit, importWeights: () -> Unit, requestNotifications: () -> Unit) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Track?>(null) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text("IS", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text("IgnorantShuffle", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(if (m.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                    Text(if (m.connected) "AIMP connected · ${if (m.automatic) "timed" else "manual"}" else "AIMP not connected",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("0.2.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.background) {
            pages.forEachIndexed { index, title ->
                Tab(selected = index == pager.currentPage, onClick = { scope.launch { pager.animateScrollToPage(index) } }, text = { Text(title) })
            }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> PlayerPage(m, settings, openAimp) { editing = it }
                1 -> TracksPage(m, importWeights, exportWeights) { editing = it }
                2 -> ShufflePage(m)
                3 -> AlertsPage(m, requestNotifications)
                4 -> ThemePage(m)
                else -> LogsPage(m, copy, exportLog)
            }
        }
    }
    editing?.let { track -> WeightDialog(m, track) { editing = null } }
}

@Composable
private fun Section(title: String? = null, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Intro(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun AimpController.ready() = connected && !pending && storageError == null
private val pagePadding get() = PaddingValues(16.dp)

@Composable
private fun PlayerPage(m: AimpController, access: () -> Unit, openAimp: () -> Unit, edit: (Track) -> Unit) {
    val ready = m.ready()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!m.connected || !m.permission) item {
            Section("Connect to AIMP") {
                Text(m.status)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!m.permission) Button(onClick = access) { Text("Notification access") }
                    OutlinedButton(onClick = openAimp) { Text("Open AIMP") }
                }
            }
        }
        item {
            Section("NOW PLAYING") {
                Text(m.playback.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (m.playback.artist.isNotBlank()) Text(m.playback.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${stateText(m.playback.state)} · ${clock(m.playback.position)} / ${clock(m.playback.duration)} reported",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { m.basic(true) }, enabled = ready && m.supportsBasic(true) && m.playback.state != 3) { Text("Play") }
                    OutlinedButton(onClick = { m.basic(false) }, enabled = ready && m.supportsBasic(false) && m.playback.state != 2) { Text("Pause") }
                    m.playback.track?.let { track -> TextButton(onClick = { edit(track) }, enabled = !m.pending) { Text("Weight ${m.weight(track)}") } }
                }
            }
        }
        item {
            Section("NEXT ROLL #${m.rollSequence}", accent = true) {
                val choice = m.preview
                Text(choice.track?.title ?: "No eligible next track", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                choice.track?.artist?.takeIf(String::isNotBlank)?.let { Text(it) }
                Text(if (choice.historyIndex != null) "Forward in listening history" else
                    "${String.format(Locale.ROOT, "%.1f", choice.probability * 100)}% at roll · ${choice.eligible} eligible · ${choice.blocked} cooling",
                    style = MaterialTheme.typography.bodySmall)
                if (choice.cooldownFallback) Text("Short playlist: cooldown fallback is active.", style = MaterialTheme.typography.bodySmall)
                if (m.settings.finite) Text("${choice.remaining} remaining in this pass", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = m::smartPrevious, enabled = ready && m.canPrevious, modifier = Modifier.weight(1f)) { Text("Previous") }
                    Button(onClick = m::smartNext, enabled = ready && choice.track != null, modifier = Modifier.weight(1f)) { Text("Smart Next") }
                }
                TextButton(onClick = m::reroll, enabled = ready && choice.track != null) { Text("Reroll next choice") }
            }
        }
        item {
            Section {
                Toggle("Timed automatic shuffle", "Experimental · switches about 300 ms early", m.automatic,
                    enabled = m.automatic || ready, onChange = m::changeAutomatic)
                Text(m.automaticStatus, style = MaterialTheme.typography.bodySmall)
                Text("CPU protection supports screen-off timing and uses more battery. It releases on pause, disable or disconnect. Headset Next keeps AIMP's own order.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text(m.result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
            m.storageError?.let { Text("Saved data/native error: $it", color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(onClick = openAimp) { Text("Open AIMP") }
                TextButton(onClick = m::refresh, enabled = !m.pending) { Text("Refresh connection") }
            }
        }
    }
}

@Composable
private fun TracksPage(m: AimpController, import: () -> Unit, export: () -> Unit, edit: (Track) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val visible = remember(search, m.tracks) { m.tracks.filter { search.isBlank() || it.title.contains(search, true) || it.artist.contains(search, true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Intro("Your tracks", "${m.tracks.size} entries exposed by AIMP")
        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            label = { Text("Search title or artist") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = import, enabled = !m.pending) { Text("Import weights") }
            TextButton(onClick = export) { Text("Export weights") }
        }
        if (visible.isEmpty()) Text(if (m.tracks.isEmpty()) "Start music in AIMP and refresh to load its queue." else "No matching tracks.", Modifier.padding(vertical = 16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(visible) { _, track ->
                val current = m.playback.track?.key == track.key
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(
                    containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (current) Text("PLAYING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(track.title.ifBlank { "Untitled track" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { edit(track) }, enabled = !m.pending && m.storageError == null) { Text("Weight ${m.weight(track)}") }
                            TextButton(onClick = { m.play(track) }, enabled = m.ready() && track.selectable) { Text("Play now") }
                            Text(if (m.weights.containsKey(track.key)) "Custom" else "Default", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item { Text("Track identity is based on AIMP's ID and metadata. Library edits may require weight reassignment.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ShufflePage(m: AimpController) {
    var default by rememberSaveable(m.settings.defaultWeight) { mutableStateOf(m.settings.defaultWeight.toString()) }
    var cooldown by rememberSaveable(m.settings.cooldown) { mutableStateOf(m.settings.cooldown.toString()) }
    var finite by rememberSaveable(m.settings.finite) { mutableStateOf(m.settings.finite) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Intro("Shuffle rules", "Shared selection rules with the Windows plugin") }
        item {
            Section("Weights & cooldown") {
                OutlinedTextField(default, { default = it }, label = { Text("Default weight") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("0 excludes unconfigured tracks. Fractions and values above 10 are supported.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(cooldown, { cooldown = it }, label = { Text("Cooldown in track starts") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("0 turns cooldown off. Short lists fall back to positive-weight choices.", style = MaterialTheme.typography.bodySmall)
                Toggle("Finite pass", "Play each positive identity once, then complete the pass", finite, onChange = { finite = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val value = Settings(default.toDoubleOrNull() ?: Double.NaN, cooldown.toIntOrNull() ?: -1, finite)
                    runCatching { value.validate() }.onSuccess { error = null; m.configure(value) }.onFailure { error = it.message }
                }, enabled = !m.pending && m.storageError == null, modifier = Modifier.fillMaxWidth()) { Text("Apply shuffle settings") }
            }
        }
        if (m.settings.finite) item {
            Section("Current pass") {
                Text("${m.passCount} identities heard · ${m.preview.remaining} positive tracks remaining")
                OutlinedButton(onClick = m::newPass, enabled = !m.pending) { Text("Start a new pass") }
            }
        }
        item {
            Section("How it behaves") {
                Text("Preview and reroll do not consume a track. Only observed playback advances history and cooldown.")
                Text("Weight 0 excludes weighted choices; you can still use Play now on any exposed track.")
                Text("Timed mode requests Pause at the estimated end when the pass completes or no eligible tracks remain.")
            }
            Spacer(Modifier.height(8.dp)); Text(m.result, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AlertsPage(m: AimpController, permission: () -> Unit) {
    val stats = m.preferences.stats
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Intro("Roll notifications", "An optional, quiet preview of the next weighted choice") }
        item {
            Section {
                Toggle("Show next-roll notification", "Updates one notification when the roll changes", m.preferences.notifyRoll, onChange = {
                    m.changePreferences(m.preferences.copy(notifyRoll = it))
                    if (it && !m.notificationsAllowed) permission()
                })
                Text(m.notificationStatus, style = MaterialTheme.typography.bodySmall)
                if (m.preferences.notifyRoll && !m.notificationsAllowed) OutlinedButton(onClick = permission) { Text("Allow / Android notification settings") }
            }
        }
        item {
            Section("Include in the notification") {
                Toggle("Artist", "Alongside the next track title", stats.artist) { m.changePreferences(m.preferences.copy(stats = stats.copy(artist = it))) }
                Toggle("Weight", "Effective weight of the selected track", stats.weight) { m.changePreferences(m.preferences.copy(stats = stats.copy(weight = it))) }
                Toggle("Probability", "Chance when the roll was made", stats.chance) { m.changePreferences(m.preferences.copy(stats = stats.copy(chance = it))) }
                Toggle("Pool counts", "Eligible and excluded candidates", stats.pool) { m.changePreferences(m.preferences.copy(stats = stats.copy(pool = it))) }
                Toggle("Cooldown", "Cooling tracks and fallback state", stats.cooldown) { m.changePreferences(m.preferences.copy(stats = stats.copy(cooldown = it))) }
                Toggle("Remaining tracks", "Shown for finite passes", stats.remaining) { m.changePreferences(m.preferences.copy(stats = stats.copy(remaining = it))) }
                Toggle("Playback mode", "Timed/manual and finite/endless", stats.mode) { m.changePreferences(m.preferences.copy(stats = stats.copy(mode = it))) }
            }
        }
        item { Text("Notifications have no media-session controls, so AIMP keeps headset and lock-screen priority. Track details are marked private on the lock screen; Android channel settings still apply.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ThemePage(m: AimpController) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Intro("Make it yours", "Theme and accent changes are saved immediately") }
        item {
            Section("Appearance") {
                ThemeMode.entries.forEach { mode ->
                    val text = when (mode) { ThemeMode.SYSTEM -> "Follow system"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }
                    FilterChip(selected = m.preferences.theme == mode, onClick = { m.changePreferences(m.preferences.copy(theme = mode)) },
                        label = { Text(text) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Section("Accent color") {
                Accent.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { accent ->
                            FilterChip(selected = m.preferences.accent == accent,
                                onClick = { m.changePreferences(m.preferences.copy(accent = accent)) },
                                label = { Text(accent.label) }, modifier = Modifier.weight(1f),
                                leadingIcon = { Box(Modifier.size(14.dp).background(Color(accent.argb), CircleShape)) })
                        }
                    }
                }
                Text("AIMP orange is the default. The accent also colors your next-roll notification.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LogsPage(m: AimpController, copy: () -> Unit, export: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("All") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = m.events.asReversed().filter { line ->
        line.contains(query, ignoreCase = true) && when (group) {
            "Rolls" -> line.contains("ROLL") || line.contains("HANDOFF")
            "Commands" -> listOf("COMMAND", "DISPATCH", "VERIFY", "RESULT").any { line.contains(it) }
            "Errors" -> listOf("ERROR", "EXCEPTION", "failed", "CANCELLED").any { line.contains(it, true) }
            else -> true
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Intro("Automatic diagnostics", "Persistent logs · newest events first")
        Toggle("Log automatically", "Enabled by default · retains two files of about 2MB", m.preferences.logging,
            onChange = { m.changePreferences(m.preferences.copy(logging = it)) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = export) { Text("Export full log") }
            OutlinedButton(onClick = copy) { Text("Copy summary") }
        }
        m.logError?.let { Text("Log storage error: $it", color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(query, { query = it }, label = { Text("Search log") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Rolls", "Commands", "Errors").forEach { name -> FilterChip(group == name, { group = name }, label = { Text(name) }) }
        }
        Text("Showing ${visible.size} of the latest ${m.events.size} events. Tap an entry to expand; export includes older saved events and the queue snapshot.", style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(visible) { _, line ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = if (expanded == line) null else line }) {
                    SelectionContainer {
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            maxLines = if (expanded == line) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun WeightDialog(m: AimpController, track: Track, close: () -> Unit) {
    var text by remember(track.key) { mutableStateOf(m.weight(track).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text(track.title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("0 excludes weighted choices. Fractions and values above 10 are supported.")
            OutlinedTextField(text, { text = it; error = null }, label = { Text("Weight") }, singleLine = true, isError = error != null)
            Row { listOf(0, 1, 5, 10).forEach { value -> TextButton(onClick = { text = value.toString() }) { Text("$value") } } }
            TextButton(onClick = {
                m.setWeight(track, "", inherit = true)
                if (!m.weights.containsKey(track.key)) close() else error = m.result
            }) { Text("Inherit default (${m.settings.defaultWeight})") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val value = text.toDoubleOrNull()
        if (value == null || !value.isFinite() || value < 0) error = "Enter a finite nonnegative number"
        else { m.setWeight(track, text); if (m.weights[track.key] == value) close() else error = m.result }
    }) { Text("Save weight") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

private fun clock(ms: Long): String = if (ms < 0) "unknown" else "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
private fun stateText(state: Int) = when (state) { 3 -> "Playing"; 2 -> "Paused"; 1 -> "Stopped"; 6 -> "Buffering"; else -> "State $state" }
