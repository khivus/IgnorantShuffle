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

private val pages = listOf("Player", "Shuffle", "Playlist", "Alerts", "Theme", "Logs")

@Composable
internal fun ShuffleScreen(m: AimpController, settings: () -> Unit, openAimp: () -> Unit, copy: () -> Unit,
    exportLog: () -> Unit, exportWeights: () -> Unit, importWeights: () -> Unit, requestNotifications: () -> Unit,
    chooseMusicFolder: () -> Unit, updates: AppUpdates) {
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
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = updates::open, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
                        Text(if (updates.release != null) "Update available" else "Updates", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("Core ${remember { ShuffleNative.coreVersion() }}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                1 -> ShufflePage(m, chooseMusicFolder)
                2 -> PlaylistPage(m, importWeights, exportWeights) { editing = it }
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

private fun AimpController.ready() = connected && !pending && storageError == null && !folder.blocked
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
                choice.track?.let { Text("Weight ${m.weight(it)}", fontWeight = FontWeight.SemiBold) }
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
                Toggle("Timed automatic shuffle", "Experimental · ${m.preferences.leadMs} ms before estimated end", m.automatic,
                    enabled = m.automatic || ready, onChange = m::changeAutomatic)
                Text(m.automaticStatus, style = MaterialTheme.typography.bodySmall)
                Text("CPU protection supports screen-off timing and uses more battery. It releases on pause, disable or disconnect. Headset Next keeps AIMP's own order.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text(m.result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
            m.storageError?.let { Text("Saved data/native error: $it", color = MaterialTheme.colorScheme.error) }
            if (m.folder.blocked) Text(m.folder.error ?: m.folder.status, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = openAimp) { Text("Open AIMP") }
                TextButton(onClick = m::refresh, enabled = !m.pending) { Text("Refresh connection") }
            }
        }
    }
}

@Composable
private fun PlaylistPage(m: AimpController, import: () -> Unit, export: () -> Unit, edit: (Track) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var linking by remember { mutableStateOf<Track?>(null) }
    val visible = remember(search, m.tracks) { m.tracks.filter { search.isBlank() || it.title.contains(search, true) || it.artist.contains(search, true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Intro("Playlist", "${m.tracks.size} entries exposed by AIMP")
        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            label = { Text("Search title or artist") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = import, enabled = !m.pending && !m.folder.blocked) { Text("Import weights") }
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
                            FilledTonalButton(onClick = { edit(track) }, enabled = !m.pending && m.storageError == null && !m.folder.blocked) { Text("Weight ${m.weight(track)}") }
                            TextButton(onClick = { m.play(track) }, enabled = m.ready() && track.selectable) { Text("Play now") }
                        }
                        Text(m.weightSource(track), style = MaterialTheme.typography.labelSmall)
                        if (m.folder.active) {
                            val match = m.folder.match(track)
                            Text(match.path ?: match.description, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { linking = track }, enabled = !m.pending && !m.folder.busy && m.folder.files.isNotEmpty()) {
                                Text(if (match.path == null) "Link music file" else "Change file link")
                            }
                        }
                    }
                }
            }
            item { Text("Shared weights use real paths relative to your music folder. Unmatched entries keep local weights until linked; ambiguous tags are never linked automatically.", style = MaterialTheme.typography.bodySmall) }
        }
    }
    linking?.let { track -> FileLinkDialog(m, track) { linking = null } }
}

@Composable
private fun ShufflePage(m: AimpController, chooseMusicFolder: () -> Unit) {
    var default by rememberSaveable(m.settings.defaultWeight) { mutableStateOf(m.settings.defaultWeight.toString()) }
    var cooldown by rememberSaveable(m.settings.cooldown) { mutableStateOf(m.settings.cooldown.toString()) }
    var finite by rememberSaveable(m.settings.finite) { mutableStateOf(m.settings.finite) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var lead by rememberSaveable(m.preferences.leadMs) { mutableStateOf(m.preferences.leadMs.toString()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Intro("Shuffle rules", "Shared selection rules with the Windows plugin") }
        item {
            Section("Music-folder weights") {
                Toggle("Use music-folder file", "Default · synchronize the same file and relative music paths with Windows", m.folder.config.enabled,
                    enabled = !m.folder.busy && !m.pending, onChange = m.folder::enable)
                Text(m.folder.config.label)
                Text(m.folder.status, style = MaterialTheme.typography.bodySmall)
                m.folder.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (m.folder.config.root == null) Text("Choose your main music folder once. Local weights work until a folder is selected.", style = MaterialTheme.typography.bodySmall)
                Text("File: ${PortableWeights.FILENAME}. Keep the same relative folder/file names on both devices. Reindex after adding or retagging music; link unresolved entries in Playlist.", style = MaterialTheme.typography.bodySmall)
                val enabled = !m.folder.busy && !m.pending
                OutlinedButton(onClick = chooseMusicFolder, enabled = enabled) { Text("Choose music folder") }
                if (m.folder.config.root != null) {
                    Text("${m.tracks.count { m.folder.match(it).path != null }}/${m.tracks.size} playlist entries linked to files", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { m.folder.reload() }, enabled = enabled && m.folder.active) { Text("Reload weights") }
                        TextButton(onClick = { m.folder.reload(true) }, enabled = enabled) { Text("Reindex files") }
                    }
                    TextButton(onClick = m::copyLocalWeights, enabled = enabled && m.folder.active && m.folder.error == null) { Text("Copy missing local weights") }
                    Text("Copies linked entries only; existing shared values, including 0, win. Unmatched weights stay local.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Section("Automatic switch timing") {
                OutlinedTextField(lead, { lead = it }, label = { Text("Lead before track end (ms)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 50, 100, 300).forEach { ms ->
                        FilterChip(selected = lead == ms.toString(), onClick = { lead = ms.toString() }, label = { Text("$ms") })
                    }
                }
                Text("0–5000 ms; default 300. Smaller values preserve more of the ending but may let AIMP advance first. Try 100, then 50 or 0; compare exported timing logs.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val ms = lead.toLongOrNull()
                    if (ms == null || ms !in 0..5000) error = "Enter 0–5000 ms"
                    else { error = null; m.changePreferences(m.preferences.copy(leadMs = ms)) }
                }, enabled = !m.pending) { Text("Apply timing") }
            }
        }
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
                Text("Show notification when:", style = MaterialTheme.typography.titleSmall)
                RollVisibility.entries.forEach { visibility ->
                    FilterChip(selected = m.preferences.notificationVisibility == visibility,
                        onClick = { m.changePreferences(m.preferences.copy(notificationVisibility = visibility)) },
                        label = { Text(visibility.label) }, modifier = Modifier.fillMaxWidth())
                }
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
    var saved by remember { mutableStateOf<List<String>?>(null) }
    val source = saved ?: m.events
    val visible = source.asReversed().filter { line ->
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(saved == null, { saved = null }, label = { Text("Live") })
            FilterChip(saved != null, { saved = m.savedEvents() }, label = { Text("Saved history") })
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search log") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Rolls", "Commands", "Errors").forEach { name -> FilterChip(group == name, { group = name }, label = { Text(name) }) }
        }
        Text("${visible.size} / ${source.size} events · ${if (saved == null) "live view" else "saved snapshot; tap Saved history to reload"}. Tap an entry to expand.", style = MaterialTheme.typography.labelSmall)
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
            Text(if (m.folder.active) m.folder.match(track).path?.let { "Saves to shared file: $it" }
                ?: "No file link: this weight stays local. Link the entry in Playlist to share it." else "Saves to local app storage.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it; error = null }, label = { Text("Weight") }, singleLine = true, isError = error != null)
            Row { listOf(0, 1, 5, 10).forEach { value -> TextButton(onClick = { text = value.toString() }) { Text("$value") } } }
            TextButton(onClick = {
                m.setWeight(track, "", inherit = true)
                if (m.folder.busy || !m.weights.containsKey(track.key)) close() else error = m.result
            }) { Text("Remove override / inherit") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val value = text.toDoubleOrNull()
        if (value == null || !value.isFinite() || value < 0) error = "Enter a finite nonnegative number"
        else { m.setWeight(track, text); if (m.folder.busy || m.weights[track.key] == value) close() else error = m.result }
    }) { Text("Save weight") } }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun FileLinkDialog(m: AimpController, track: Track, close: () -> Unit) {
    var search by remember { mutableStateOf(track.title) }
    val files = remember(search, m.folder.files) { m.folder.files.filter {
        search.isBlank() || it.path.contains(search, true) || it.title.contains(search, true) || it.artist.contains(search, true)
    } }
    AlertDialog(onDismissRequest = close, title = { Text("Link ${track.title}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose the actual file in your selected music folder. The link stays on this device; its weight uses the shared relative path.")
            OutlinedTextField(search, { search = it }, label = { Text("Search file, title or artist") }, singleLine = true)
            LazyColumn(Modifier.heightIn(min = 100.dp, max = 300.dp)) {
                itemsIndexed(files) { _, file ->
                    TextButton(onClick = { m.linkFile(track, file.path); close() }, modifier = Modifier.fillMaxWidth()) { Text(file.path) }
                }
                if (files.isEmpty()) item { Text("No files match. Clear the search or reindex your folder.") }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } }, dismissButton = {
        TextButton(onClick = { m.linkFile(track, null); close() }) { Text("Automatic match") }
    })
}

private fun clock(ms: Long): String = if (ms < 0) "unknown" else "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
private fun stateText(state: Int) = when (state) { 3 -> "Playing"; 2 -> "Paused"; 1 -> "Stopped"; 6 -> "Buffering"; else -> "State $state" }
