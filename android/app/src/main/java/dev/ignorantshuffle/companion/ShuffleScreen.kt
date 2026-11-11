package dev.ignorantshuffle.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale


private val categoryPages = linkedMapOf(
    "Listen" to listOf("Player", "Playlist", "Queue", "Statistics"),
    "Settings" to listOf("Shuffle rules", "Music folders", "Playback", "Notifications", "Appearance"),
    "Support" to listOf("Diagnostics", "Logs", "Updates"))
private fun navigationCategory(page: String) = categoryPages.entries.first { page in it.value }.key

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShuffleScreen(m: AimpController, settings: () -> Unit, openAimp: () -> Unit, copy: () -> Unit,
    exportLog: () -> Unit, exportWeights: () -> Unit, importWeights: () -> Unit, requestNotifications: () -> Unit,
    chooseMusicFolder: () -> Unit, batteryExempt: Boolean, batterySettings: () -> Unit, updates: AppUpdates,
    exportStatistics: (String) -> Unit) {
    var page by rememberSaveable { mutableStateOf("Player") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Track?>(null) }
    val pageState = rememberSaveableStateHolder()
    BackHandler(drawer.isOpen || page != "Player") {
        if (drawer.isOpen) scope.launch { drawer.close() } else page = "Player"
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                Text("IgnorantShuffle", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                listOf("Listen", "Settings", "Support").forEach { category ->
                    NavigationDrawerItem(label = { Text(category) }, selected = navigationCategory(page) == category,
                        onClick = { page = categoryPages.getValue(category).first(); scope.launch { drawer.close() } })
                }

            }
        }
    }) {
        Scaffold(modifier = Modifier.fillMaxSize(), snackbarHost = { ActionFeedback(m) }, topBar = {
            TopAppBar(title = { Text(page, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(painterResource(R.drawable.ic_menu), "Open navigation drawer") }
            }, actions = {
                Row(Modifier.padding(end=12.dp).semantics(mergeDescendants=true) {
                    contentDescription = if (m.connected) "AIMP connected" else "AIMP disconnected"
                },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(if(m.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,CircleShape))
                    Text(if(m.connected) "Connected" else "Disconnected",style=MaterialTheme.typography.labelSmall,maxLines=1)
                }
            })
        }, bottomBar = {
            Surface(modifier = Modifier.navigationBarsPadding(), tonalElevation = 3.dp) {
                PrimaryScrollableTabRow(selectedTabIndex = categoryPages.getValue(navigationCategory(page)).indexOf(page), edgePadding = 8.dp) {
                    categoryPages.getValue(navigationCategory(page)).forEach { title ->
                        Tab(selected = page == title, onClick = { page = title }, text = { Text(if (title == "Statistics") "Stats" else title) })
                    }
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                m.debugIssue?.let { issue ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(issue, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = m::dismissDebugIssue) { Text("Dismiss") }
                        }
                    }
                }
                Box(Modifier.weight(1f)) { pageState.SaveableStateProvider(page) {
                    when (page) {
                        "Player" -> PlayerPage(m, settings, openAimp, batteryExempt, batterySettings) { editing = it }
                        "Playlist" -> PlaylistPage(m) { editing = it }
                        "Queue" -> QueuePage(m)
                        "Statistics" -> StatisticsPage(m, exportStatistics)
                        "Shuffle rules", "Music folders", "Playback" -> ShufflePage(m, chooseMusicFolder, importWeights, exportWeights, page, batteryExempt, batterySettings)
                        "Notifications" -> AlertsPage(m, requestNotifications)
                        "Appearance" -> ThemePage(m)
                        "Diagnostics" -> DiagnosticsPage(m, requestNotifications)
                        "Logs" -> LogsPage(m, copy, exportLog)
                        "Updates" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Intro("About and updates", "Installed Android v${BuildConfig.VERSION_NAME}")
                            Section {
                                Text(updates.notice)
                                ActionButton(onClick = { if(updates.release != null || updates.busy) updates.open() else updates.check(true) }) { Text("Manage updates") }
                            }
                        }
                    }
                } }
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
private fun PlayerPage(m: AimpController, access: () -> Unit, openAimp: () -> Unit,
    batteryExempt: Boolean, batterySettings: () -> Unit, edit: (Track) -> Unit) {
    val ready = m.ready()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!m.connected || !m.permission) item {
            Section("Connect to AIMP") {
                Text(m.status)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!m.permission) ActionButton(onClick = access) { Text("Notification access") }
                    ActionButton(onClick = openAimp) { Text("Open AIMP") }
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
                    ActionButton(onClick = { m.basic(true) }, enabled = ready && m.supportsBasic(true) && m.playback.state != 3) { Text("Play") }
                    ActionButton(onClick = { m.basic(false) }, enabled = ready && m.supportsBasic(false) && m.playback.state != 2) { Text("Pause") }
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
                Text(if (choice.queueEntryId != null) "Next in app queue" else if (choice.historyIndex != null) "Forward in listening history" else
                    "${String.format(Locale.ROOT, "%.1f", choice.probability * 100)}% at roll · ${choice.eligible} eligible · ${choice.blocked} cooling",
                    style = MaterialTheme.typography.bodySmall)
                if (choice.cooldownFallback) Text("Short playlist: cooldown fallback is active.", style = MaterialTheme.typography.bodySmall)
                if (m.settings.finite) Text("${choice.remaining} remaining in this pass", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(onClick = m::smartPrevious, enabled = ready && m.canPrevious, modifier = Modifier.weight(1f)) { Text("Previous") }
                    ActionButton(onClick = m::smartNext, enabled = ready && choice.track != null, modifier = Modifier.weight(1f)) { Text("Play") }
                }
                TextButton(onClick = m::reroll, enabled = ready && choice.track != null && choice.queueEntryId == null) { Text("Reroll next choice") }
            }
        }
        item {
            Section {
                Toggle("Enable weighted shuffle", if (m.preferences.reactivePlayback)
                    "Combined: switch ${m.preferences.leadMs} ms before track end and correct early AIMP skips after ${m.preferences.correctionDelayMs} ms while AIMP Shuffle is on."
                    else "Automatically play the weighted choice ${m.preferences.leadMs} ms before track end while AIMP Shuffle is on.", m.automatic,
                    enabled = m.automatic || ready, onChange = m::changeAutomatic)
                Text(m.automaticStatus, style = MaterialTheme.typography.bodySmall)
                Text(if (m.preferences.reactivePlayback)
                    "AIMP Next and headset skips are corrected too. To keep a specific track, select it again within 5 seconds after correction. CPU protection uses more battery."
                    else "CPU protection supports screen-off timing and uses more battery. It releases on pause, disable or disconnect. Headset Next keeps AIMP's own order.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text(m.result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
            m.storageError?.let { Text("Saved data/native error: $it", color = MaterialTheme.colorScheme.error) }
            if (m.folder.blocked) Text(m.folder.error ?: m.folder.status, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(onClick = openAimp, modifier = Modifier.weight(1f)) { Text("Open AIMP") }
                ActionButton(onClick = m::refresh, enabled = !m.pending, modifier = Modifier.weight(1f)) { Text("Refresh connection") }
            }
        }
    }
}

@Composable
private fun PlaylistPage(m: AimpController, edit: (Track) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var linking by remember { mutableStateOf<Track?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val visible = remember(search, m.tracks) { m.tracks.filter { search.isBlank() || it.title.contains(search, true) || it.artist.contains(search, true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {

        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            label = { Text("Search ${m.tracks.size} tracks by title or artist") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        if (visible.isEmpty()) Text(if (m.tracks.isEmpty()) "Start music in AIMP and refresh to load its playlist." else "No matching tracks.", Modifier.padding(vertical = 16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(visible, key = { index, track -> "${track.key}:$index" }, contentType = { _, _ -> "track" }) { _, track ->
                val current = m.playback.track?.key == track.key
                Card(modifier = Modifier.fillMaxWidth().clickable { expanded = if (expanded == track.key) null else track.key }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(
                    containerColor = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (current) Text("PLAYING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text((m.appQueue.mapIndexedNotNull { i, entry -> if (entry.key == track.key) "[${i + 1}]" else null }.joinToString(" ").let { if (it.isBlank()) "" else "$it " }) + track.title.ifBlank { "Untitled track" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { edit(track) }, modifier = Modifier.weight(1f), enabled = !m.pending && m.storageError == null && !m.folder.blocked) { Text("Weight ${m.weight(track)}") }
                            ActionButton(onClick = { m.play(track) }, modifier = Modifier.weight(1f), enabled = m.ready() && track.selectable) { Text("Play now") }
                        }
                        TextButton(onClick = { m.enqueue(track) }, enabled = track.selectable && !m.pending) { Text("Add to queue") }
                        if (expanded == track.key) {
                        if(m.preferences.smartWeights.enabled) {
                            val score=m.statistics.smartScores[m.statisticsKey(track)]?:m.statistics.smartScores[track.key]?:0.0
                            Text("Smart adjustment: ${String.format(Locale.ROOT,"%+.2f%%",(m.preferences.smartWeights.factor(score)-1.0)*100)}",style=MaterialTheme.typography.bodySmall)
                        }
                        if(m.preferences.balanceListening || m.preferences.smartWeights.enabled)
                            Text("Selection weight for future rolls: ${String.format(Locale.ROOT,"%.2f",m.selectionWeight(track))} (saved weight ${m.weight(track)})",style=MaterialTheme.typography.bodySmall)
                        Text(m.weightSource(track), style = MaterialTheme.typography.labelSmall)
                        if (m.weight(track) < m.settings.minimumWeight)
                            Text("Excluded from shuffle (weight < ${m.settings.minimumWeight})", style = MaterialTheme.typography.bodySmall)
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
            }
            item { Text("Tap a track for file and weight details. Shared weights use real paths relative to your music folder. Unmatched entries use the default weight until linked; ambiguous tags require a file link.", style = MaterialTheme.typography.bodySmall) }
        }
    }
    linking?.let { track -> FileLinkDialog(m, track) { linking = null } }
}

@Composable
private fun ShufflePage(m: AimpController, chooseMusicFolder: () -> Unit, importWeights: () -> Unit, exportWeights: () -> Unit, group: String, batteryExempt: Boolean, batterySettings: () -> Unit) {
    var default by rememberSaveable(m.settings.defaultWeight) { mutableStateOf(m.settings.defaultWeight.toString()) }
    var minimum by rememberSaveable(m.settings.minimumWeight) { mutableStateOf(m.settings.minimumWeight.toString()) }
    var cooldown by rememberSaveable(m.settings.cooldown) { mutableStateOf(m.settings.cooldown.toString()) }
    var finite by rememberSaveable(m.settings.finite) { mutableStateOf(m.settings.finite) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var lead by rememberSaveable(m.preferences.leadMs) { mutableStateOf(m.preferences.leadMs.toString()) }
    var correctionDelay by rememberSaveable(m.preferences.correctionDelayMs) { mutableStateOf(m.preferences.correctionDelayMs.toString()) }
    var correctionError by rememberSaveable { mutableStateOf<String?>(null) }
    var removingRoot by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Intro(group, "Changes are saved on this device") }
        if (group == "Music folders") item {
            Section("Shared settings") {
                Toggle("Share settings between devices", "Off by default. Sync the settings file with your music. Rules and learning options are shared; appearance, permissions and playback controls stay local.", m.shareSettings, onChange = m::changeSharedSettings)
                Text(m.sharedSettingsStatus, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = m::reloadSharedSettings, enabled = m.shareSettings) { Text("Reload shared") }
                    TextButton(onClick = m::publishSharedSettings, enabled = m.shareSettings) { Text("Publish current settings") }
                }
            }
        }
        if (group == "Playback") item {
            Section("Background playback") {
                Text(if (batteryExempt) "Battery optimization is disabled for IgnorantShuffle." else
                    "Battery optimization is enabled. Android may delay background shuffle and let AIMP skip the weighted choice.")
                Text("For reliable screen-off switching, allow unrestricted battery use for IgnorantShuffle. In Android settings, choose this app and select Don't optimize / Unrestricted. Some phones also have a separate background or auto-start setting.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = batterySettings) { Text(if (batteryExempt) "Battery settings" else "Disable battery optimization") }
            }
        }
        if (group == "Music folders") item {
            Section("Music-folder weights") {
                Text("Each song has its own weight: 10 gives twice the chance of 5 when both are eligible; 0 excludes a song.")
                Text("For Music/Artist/Song.flac, add Music. Each separate music root stores its song weights in ${PortableWeights.FILENAME}.", style = MaterialTheme.typography.bodySmall)
                Text(m.folder.status, style = MaterialTheme.typography.bodySmall)
                m.folder.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val enabled = !m.folder.busy && !m.pending
                ActionButton(onClick = chooseMusicFolder, enabled = enabled) { Text("Add music root") }
                val severalRoots = m.folder.config.roots.size > 1
                if (severalRoots) {
                    Text("Music root for Import / Export", style = MaterialTheme.typography.titleSmall)
                    Text("Select the destination for Import weights and the source for Export weights.", style = MaterialTheme.typography.bodySmall)
                }
                m.folder.config.roots.forEach { root ->
                    val selected = root.uri == m.folder.config.root
                    OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp,
                        if (selected && severalRoots) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val selection = if (severalRoots) Modifier.selectable(selected = selected, enabled = enabled,
                                role = Role.RadioButton, onClick = { if (!selected) m.folder.selectTransferRoot(root.uri) }) else Modifier
                            Row(Modifier.fillMaxWidth().then(selection), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(root.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                if (severalRoots) RadioButton(selected = selected, onClick = null, enabled = enabled)
                            }
                            if (selected && severalRoots) Text("Selected for Import / Export", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { removingRoot = root.uri }, enabled = enabled,
                                contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Remove music root") }
                        }
                    }
                }
                if (m.folder.active) {
                    Text("${m.tracks.count { m.folder.match(it).path != null }}/${m.tracks.size} playlist entries linked to files", style = MaterialTheme.typography.bodySmall)
                    Text("Import replaces the weights file in ${m.folder.config.label}. Export saves a copy of that file.", style = MaterialTheme.typography.bodySmall)
                } else Text("Add a music root to import or export its weights file.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = importWeights, enabled = enabled && m.folder.active && !m.folder.blocked,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Import weights") }
                    OutlinedButton(onClick = exportWeights, enabled = enabled && m.folder.active && !m.folder.blocked,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Export weights") }
                }
                if (m.folder.active) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { m.folder.reload() }, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Reload weights") }
                        TextButton(onClick = { m.folder.reload(true) }, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Reindex files") }
                    }
                }
                Text("Edit song weights in Playlist. Link unresolved songs to their files first. Reload weights after syncing the file from another device; reindex after adding or retagging music.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (group == "Playback") item {
            Section("Automatic switching") {
                Toggle("Combined switching (experimental)",
                    "Switch before a normal track end, and correct early AIMP/headset skips to the saved weighted choice. Requires Enable weighted shuffle and AIMP Shuffle.",
                    m.preferences.reactivePlayback, enabled = !m.pending,
                    onChange = { m.changePreferences(m.preferences.copy(reactivePlayback = it)) })
                if (m.preferences.reactivePlayback) {
                    OutlinedTextField(correctionDelay, { correctionDelay = it }, label = { Text("Correction delay after track change (ms)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 25, 50, 100, 200).forEach { ms ->
                            StateChip(selected = correctionDelay == ms.toString(), onClick = { correctionDelay = ms.toString() }, label = { Text("$ms") })
                        }
                    }
                    Text("0–2000 ms; default 200. Lower values reduce the audible fragment. Zero sends immediately when a different PLAYING track is identified, but may react to temporary metadata. AIMP response time still adds delay.", style = MaterialTheme.typography.bodySmall)
                    correctionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    ActionButton(onClick = {
                        val ms = correctionDelay.toLongOrNull()
                        if (ms == null || ms !in 0..ReactivePlayback.MAX_DELAY_MS) correctionError = "Enter 0–2000 ms"
                        else { correctionError = null; m.changePreferences(m.preferences.copy(correctionDelayMs = ms)) }
                    }, enabled = !m.pending) { Text("Apply correction delay") }
                    Text("You may hear a short fragment before correction. To choose a specific track in AIMP, select it again within 5 seconds after the weighted track starts. The second selection is allowed to play.", style = MaterialTheme.typography.bodySmall)
                    Text("One correction per change; an unconfirmed command stops automatic switching. Keep battery optimization disabled for screen-off playback.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (group == "Playback") item {
            Section("Switch before track end") {
                OutlinedTextField(lead, { lead = it }, label = { Text("Lead before track end (ms)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 50, 100, 300).forEach { ms ->
                        StateChip(selected = lead == ms.toString(), onClick = { lead = ms.toString() }, label = { Text("$ms") })
                    }
                }
                Text("0–5000 ms; default 300. Smaller values preserve more of the ending but may let AIMP advance first. Try 100, then 50 or 0; compare exported timing logs.", style = MaterialTheme.typography.bodySmall)
                ActionButton(onClick = {
                    val ms = lead.toLongOrNull()
                    if (ms == null || ms !in 0..5000) error = "Enter 0–5000 ms"
                    else { error = null; m.changePreferences(m.preferences.copy(leadMs = ms)) }
                }, enabled = !m.pending) { Text("Apply timing") }
            }
        }
        if (group == "Shuffle rules") item {
            Section("Listening balance") {
                Toggle("Balance listening frequency", "Less-played tracks gain selection weight; often-played tracks lose it. Uses all-time qualified local plays, with a 0.25–4× multiplier. Saved weights and eligibility are unchanged.", m.preferences.balanceListening,
                    enabled = !m.pending, onChange = { m.changePreferences(m.preferences.copy(balanceListening = it)) })
                if(m.preferences.balanceListening) {
                    Text("Balance strength",style=MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf(0.25 to "Gentle · 25%",0.5 to "Medium · 50%",1.0 to "Full · 100%").forEach { (strength,label) ->
                            StateChip(m.preferences.balanceStrength==strength, { if(!m.pending)m.changePreferences(m.preferences.copy(balanceStrength=strength)) },label={Text(label)})
                        }
                    }
                    Text("Lower strengths soften the adjustment. Pause Statistics to freeze learning; clear statistics to reset it.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        if(group=="Shuffle rules") item {
            val smart=m.preferences.smartWeights
            val update: (SmartWeightOptions) -> Unit = { if(!m.pending)m.changePreferences(m.preferences.copy(smartWeights=it)) }
            Section("Smart weights") {
                Toggle("Learn from listening", "Gently lower tracks skipped early and favor full listens, replays and volume increases. Saved weights stay unchanged.",smart.enabled,enabled=!m.pending) { update(smart.copy(enabled=it)) }
                if(smart.enabled) {
                    Text("Learning strength",style=MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf(0.5 to "Gentle",1.0 to "Normal",2.0 to "Stronger").forEach { (strength,label) ->
                            StateChip(smart.strength==strength,{update(smart.copy(strength=strength))},label={Text(label)})
                        }
                    }
                    Text("Maximum smart adjustment",style=MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf(0.05,0.15,0.25).forEach { limit -> StateChip(smart.limit==limit,{update(smart.copy(limit=limit))},label={Text("±${(limit*100).toInt()}%")}) }
                    }
                    Text("At normal strength: early skip −2%, full listen +0.5%, replay +1%, volume up +0.25%. Total gains are capped at +1.5% per listening session.",style=MaterialTheme.typography.bodySmall)
                    Toggle("Early skips", "Leave after at least 2 seconds, before 30 seconds or half the track",smart.skips,enabled=!m.pending) { update(smart.copy(skips=it)) }
                    Toggle("Full listens", "Reach the ending after hearing at least 90% of the track",smart.completions,enabled=!m.pending) { update(smart.copy(completions=it)) }
                    Toggle("Replays", "An observed repeat or app Previous/replay must qualify before gaining weight",smart.replays,enabled=!m.pending) { update(smart.copy(replays=it)) }
                    Toggle("Volume increases", "One small gain per qualified session; initial readings and detected device changes are ignored",smart.volume,enabled=!m.pending) { update(smart.copy(volume=it)) }
                    Text("Can work with listening balance. Zero and below-minimum saved weights stay excluded. Pause Statistics to freeze learning; clear statistics to reset learned adjustments. New feedback affects future rolls.",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (group == "Shuffle rules") item {
            Section("Weights & cooldown") {
                OutlinedTextField(default, { default = it }, label = { Text("Default weight") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Used for tracks without a saved weight. Fractions and values above 10 are supported.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(minimum, { minimum = it }, label = { Text("Minimum eligible weight") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Default 1: weights 1 and above are eligible. The minimum must be greater than 0; fractions such as 0.1 are allowed. Weights below it are excluded. Play now stays available.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(cooldown, { cooldown = it }, label = { Text("Cooldown in track starts") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("0 turns cooldown off. Short lists fall back to choices at or above the minimum weight.", style = MaterialTheme.typography.bodySmall)
                Toggle("Finite pass", "Play each eligible identity once, then complete the pass", finite, onChange = { finite = it })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ActionButton(onClick = {
                    val value = Settings(default.toDoubleOrNull() ?: Double.NaN, cooldown.toIntOrNull() ?: -1, finite,
                        minimum.toDoubleOrNull() ?: Double.NaN)
                    runCatching { value.validate() }.onSuccess { error = null; m.configure(value) }.onFailure { error = it.message }
                }, enabled = !m.pending && m.storageError == null, modifier = Modifier.fillMaxWidth()) { Text("Apply shuffle settings") }
            }
        }
        if (group == "Shuffle rules" && m.settings.finite) item {
            Section("Current pass") {
                Text("${m.passCount} identities heard · ${m.preview.remaining} eligible tracks remaining")
                ActionButton(onClick = m::newPass, enabled = !m.pending) { Text("Start a new pass") }
            }
        }
        if (group == "Shuffle rules") item {
            Section("How it behaves") {
                Text("Preview and reroll do not consume a track. Only observed playback advances history and cooldown.")
                Text("Weights below ${m.settings.minimumWeight} are excluded from weighted choices; you can still use Play now on any exposed track.")
                Text(if (m.preferences.reactivePlayback)
                    "When the pass completes or no eligible tracks remain, Pause is requested at the estimated end, or after an early AIMP skip. Rejected fragments do not count toward history or the pass."
                    else "Weighted shuffle requests Pause at the estimated end when the pass completes or no eligible tracks remain.")
            }
            Spacer(Modifier.height(8.dp)); Text(m.result, style = MaterialTheme.typography.bodySmall)
        }
    }
    m.folder.config.roots.firstOrNull { it.uri == removingRoot }?.let { root ->
        AlertDialog(onDismissRequest = { removingRoot = null },
            title = { Text("Remove music root?") },
            text = { Text("Remove ${root.label} from IgnorantShuffle? Music files and the saved weights file stay in that folder. Adding it again will require indexing its music files.") },
            confirmButton = { TextButton(onClick = { m.folder.removeRoot(root.uri); removingRoot = null },
                enabled = !m.folder.busy && !m.pending) { Text("Remove music root") } },
            dismissButton = { TextButton(onClick = { removingRoot = null }) { Text("Cancel") } })
    }

}

@Composable
private fun AlertsPage(m: AimpController, permission: () -> Unit) {
    val stats = m.preferences.stats
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Intro("Roll notifications", "An optional, quiet preview of the next weighted choice") }
        item {
            Section {
                Toggle("Show next-roll notification", "Weighted Next and listening-history Previous controls", m.preferences.notifyRoll, onChange = {
                    m.changePreferences(m.preferences.copy(notifyRoll = it))
                    if (it && !m.notificationsAllowed) permission()
                })
                Text(m.notificationStatus, style = MaterialTheme.typography.bodySmall)
                if (m.preferences.notifyRoll && !m.notificationsAllowed) ActionButton(onClick = permission) { Text("Allow / Android notification settings") }
                Toggle("Show Next button", "Play the displayed weighted choice from the notification", m.preferences.notificationNext,
                    onChange = { m.changePreferences(m.preferences.copy(notificationNext = it)) })
                Toggle("Show Previous button", "Return through listening history from the notification", m.preferences.notificationPrevious,
                    onChange = { m.changePreferences(m.preferences.copy(notificationPrevious = it)) })
                TextButton(onClick = permission) { Text("Android notification settings") }
                Text("Show notification when:", style = MaterialTheme.typography.titleSmall)
                RollVisibility.entries.forEach { visibility ->
                    StateChip(selected = m.preferences.notificationVisibility == visibility,
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
        item { Text("Expand the notification to see enabled buttons. Next plays the displayed weighted choice; Previous returns through confirmed listening history when available. Both hide while a command is pending. Android controls notification display while the phone is locked. Combined switching can correct early skips from AIMP's media card and headset buttons.", style = MaterialTheme.typography.bodySmall) }
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
                    StateChip(selected = m.preferences.theme == mode, onClick = { m.changePreferences(m.preferences.copy(theme = mode)) },
                        label = { Text(text) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            Section("Accent color") {
                Accent.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { accent ->
                            StateChip(selected = m.preferences.accent == accent,
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
private fun DiagnosticsPage(m: AimpController, permission: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Section("Playback & logging") {
        Toggle("Playback debug mode", "Check selected tracks against actual playback; warn here and by notification", m.preferences.debugPlayback,
            onChange = {
                m.changePreferences(m.preferences.copy(debugPlayback = it))
                if (it && !m.debugNotificationsAllowed) permission()
            })
        if (m.preferences.debugPlayback) {
            Text(m.debugStatus, style = MaterialTheme.typography.bodySmall)
            if (!m.debugNotificationsAllowed) TextButton(onClick = permission) { Text("Allow debug notifications") }
        }
        Toggle("Log automatically", "Enabled by default · retains two files of about 2MB", m.preferences.logging,
            onChange = { m.changePreferences(m.preferences.copy(logging = it)) })
        } }
    }
}

@Composable
private fun LogsPage(m: AimpController, copy: () -> Unit, export: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("All") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<List<String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val source = saved ?: m.events
    val visible by produceState(emptyList<String>(), source, query, group) {
        value = withContext(Dispatchers.Default) { source.asReversed().filter { line ->
            line.contains(query, ignoreCase = true) && when (group) {
                "Rolls" -> line.contains("ROLL") || line.contains("HANDOFF")
                "Commands" -> listOf("COMMAND", "DISPATCH", "VERIFY", "RESULT").any { line.contains(it) }
                "Errors" -> listOf("ERROR", "EXCEPTION", "failed", "CANCELLED").any { line.contains(it, true) }
                else -> true
            }
        } }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, label = { Text("Search log") }, singleLine = true, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }) { Text("Tools") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Export full log") }, onClick = { menu = false; export() })
                    DropdownMenuItem(text = { Text("Copy summary") }, onClick = { menu = false; copy() })
                    DropdownMenuItem(text = { Text("Live events") }, onClick = { saved = null; menu = false })
                    DropdownMenuItem(text = { Text("Load saved history") }, enabled = !loading, onClick = {
                        menu = false; loading = true
                        scope.launch { try { saved = withContext(Dispatchers.IO) { m.savedEvents() } } finally { loading = false } }
                    })
                    HorizontalDivider()
                    listOf("All", "Rolls", "Commands", "Errors").forEach { name ->
                        DropdownMenuItem(text = { Text(if (group == name) "✓ $name" else name) }, onClick = { group = name; menu = false })
                    }
                }
            }
        }
        Text(if (loading) "Loading saved events…" else "${visible.size}/${source.size} · $group · ${if (saved == null) "Live" else "Saved snapshot"} · Tap to expand", style = MaterialTheme.typography.labelSmall)
        m.logError?.let { Text("Log storage error: $it", color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(visible, contentType = { _, _ -> "log" }) { _, line ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clickable { expanded = if (expanded == line) null else line }) {
                    SelectionContainer {
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            maxLines = if (expanded == line) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked,enabled=enabled,role=Role.Switch,onValueChange=onChange)
        .semantics(mergeDescendants=true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun WeightDialog(m: AimpController, track: Track, close: () -> Unit) {
    var text by remember(track.key) { mutableStateOf(m.weight(track).toString()) }
    var error by remember(track.key) { mutableStateOf<String?>(null) }
    val selected=text.toDoubleOrNull()
    val colors=MaterialTheme.colorScheme
    val path=m.folder.match(track).path
    AlertDialog(onDismissRequest=close,
        modifier=Modifier.padding(horizontal=12.dp).widthIn(max=480.dp).fillMaxWidth(),
        properties=DialogProperties(usePlatformDefaultWidth=false),
        title={
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Track weight",style=MaterialTheme.typography.labelLarge,color=colors.primary)
                Text(track.title,style=MaterialTheme.typography.headlineSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(track.artist.isNotBlank()) Text(track.artist,style=MaterialTheme.typography.bodyMedium,
                    color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        },text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Quick select",style=MaterialTheme.typography.labelLarge,color=colors.onSurfaceVariant)
                    (1..10).chunked(5).forEach {values ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            values.forEach {value ->
                                WeightPreset(selected==value.toDouble(),{text=value.toString();error=null},Modifier.weight(1f)) {
                                    Text("$value",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    WeightPreset(selected==0.0,{text="0";error=null},Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("0",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            Text("Exclude from shuffle",style=MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                OutlinedTextField(text,{text=it;error=null},modifier=Modifier.fillMaxWidth(),
                    label={Text("Custom weight")},singleLine=true,isError=error!=null,shape=RoundedCornerShape(14.dp),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                    supportingText={Text(error?:"Higher weights play more often. Minimum for shuffle: ${m.settings.minimumWeight}. Fractions and values above 10 are supported.")})
                TextButton(onClick={
                    m.setWeight(track,"",inherit=true)
                    if(m.folder.busy || !m.weights.containsKey(track.key))close() else error=m.result
                },modifier=Modifier.fillMaxWidth()) {Text("Use default weight (${m.settings.defaultWeight})")}
                Surface(shape=RoundedCornerShape(12.dp),color=colors.surfaceContainerLow,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(if(path!=null) "Saves to music root" else "Music file not linked",
                            style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                        SelectionContainer {Text(path?:"Link this track to a music file before saving.",
                            style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant,
                            maxLines=3,overflow=TextOverflow.Ellipsis)}
                    }
                }
            }
        },confirmButton={
            Button(onClick={
                val value=text.toDoubleOrNull()
                if(value==null || !value.isFinite() || value<0)error="Enter a finite nonnegative number"
                else {m.setWeight(track,text);if(m.folder.busy || m.weights[track.key]==value)close() else error=m.result}
            }) {Text("Save weight")}
        },dismissButton={TextButton(onClick=close) {Text("Cancel")}})
}

@Composable
private fun WeightPreset(selected: Boolean,onClick: () -> Unit,modifier: Modifier=Modifier,content: @Composable () -> Unit) {
    val colors=MaterialTheme.colorScheme
    Surface(selected=selected,onClick=onClick,modifier=modifier,shape=RoundedCornerShape(12.dp),
        color=if(selected)colors.primary else colors.surfaceContainerHigh,
        contentColor=if(selected)colors.onPrimary else colors.onSurfaceVariant,
        border=if(selected)null else BorderStroke(1.dp,colors.outlineVariant)) {
        Box(Modifier.heightIn(min=48.dp).padding(horizontal=4.dp,vertical=8.dp),contentAlignment=Alignment.Center) {content()}
    }
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
                    TextButton(onClick = { m.linkFile(track, file.id); close() }, modifier = Modifier.fillMaxWidth()) { Text("${m.folder.config.roots.firstOrNull { it.uri == file.root }?.label.orEmpty()}/${file.path}") }
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

@Composable
private fun ActionFeedback(m: AimpController) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(m.statistics.actionSequence) { if(m.statistics.actionSequence>0) m.acknowledge(m.statistics.actionStatus) }
    LaunchedEffect(m.statistics.error) { m.statistics.error?.let(m::acknowledge) }
    LaunchedEffect(m.feedbackSequence) {
        if (m.feedbackSequence > 0) { visible = true; kotlinx.coroutines.delay(3200); visible = false }
    }
    androidx.compose.animation.AnimatedVisibility(visible) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.feedback, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { visible = false }) { Text("Dismiss") }
            }
        }
    }
}
