package dev.ignorantshuffle.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val monitor get() = (application as ShuffleApplication).controller
    private var exportText = ""
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val text = exportText
            Thread {
                try {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    runOnUiThread { monitor.log("Export completed") }
                } catch (e: Exception) { runOnUiThread { monitor.log("Export error: $e") } }
            }.start()
        }
    }
    private val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Thread {
            try {
                val data = ByteArrayOutputStream()
                requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        require(data.size() + n <= 2_000_000) { "Import is too large (maximum 2MB)" }
                        data.write(buffer, 0, n)
                    }
                }
                val text = data.toString("UTF-8")
                runOnUiThread { monitor.importWeights(text) }
            } catch (e: Exception) { runOnUiThread { monitor.log("Import error: $e") } }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) {
                ShuffleScreen(monitor, ::openSettings, ::openAimp, ::copySummary,
                    { exportText = monitor.report(true); export.launch("IgnorantShuffle-log.txt") },
                    { exportText = monitor.exportWeights(); export.launch("IgnorantShuffle-Android.weights.tsv") },
                    { import.launch(arrayOf("text/*", "application/octet-stream")) })
            } }
        }
    }
    override fun onResume() { super.onResume(); monitor.log("Activity resumed"); monitor.refresh() }
    override fun onStop() { monitor.log("Activity backgrounded"); super.onStop() }
    private fun openSettings() { runCatching { startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { monitor.log("Settings error: $it") } }
    private fun openAimp() {
        val intent = packageManager.getLaunchIntentForPackage("com.aimp.player")
        if (intent == null) monitor.log("AIMP is not installed or has no launcher activity")
        else runCatching { startActivity(intent) }.onFailure { monitor.log("AIMP launch error: $it") }
    }
    private fun copySummary() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("IgnorantShuffle", monitor.report().take(200_000)))
        monitor.log("Summary copied")
    }
}

@Composable
private fun ShuffleScreen(m: AimpController, settings: () -> Unit, openAimp: () -> Unit, copy: () -> Unit,
    exportLog: () -> Unit, exportWeights: () -> Unit, importWeights: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Track?>(null) }
    var default by remember(m.settings) { mutableStateOf(m.settings.defaultWeight.toString()) }
    var cooldown by remember(m.settings) { mutableStateOf(m.settings.cooldown.toString()) }
    var finite by remember(m.settings) { mutableStateOf(m.settings.finite) }
    val ready = m.connected && !m.pending && m.storageError == null
    val visible = m.tracks.filter { search.isBlank() || it.title.contains(search, true) || it.artist.contains(search, true) }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("IgnorantShuffle", style = MaterialTheme.typography.headlineMedium)
                Text("AIMP companion · Android preview 0.2.0")
                Text(m.status)
                Text("Notification access: ${if (m.permission) "granted" else "required"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = settings) { Text("Grant access") }
                    OutlinedButton(onClick = openAimp) { Text("Open AIMP") }
                }
                OutlinedButton(onClick = m::refresh, enabled = !m.pending) { Text("Refresh session / queue") }
                m.storageError?.let { Text("Storage/native error: $it. Playback selection is disabled; saved data was preserved.") }
                HorizontalDivider()
                Text(m.playback.title, style = MaterialTheme.typography.titleLarge)
                Text(m.playback.artist)
                Text("${stateText(m.playback.state)} · reported ${clock(m.playback.position)} / ${clock(m.playback.duration)}")
                m.playback.track?.let { Text("Current weight: ${m.weight(it)}") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { m.basic(true) }, enabled = ready && m.supportsBasic(true)) { Text("Play") }
                    OutlinedButton(onClick = { m.basic(false) }, enabled = ready && m.supportsBasic(false)) { Text("Pause") }
                }
                Text(m.result)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Next weighted choice", style = MaterialTheme.typography.titleLarge)
                val next = m.preview
                Text(next.track?.let { "${it.title} — ${it.artist}" } ?: "No eligible tracks / pass complete")
                Text(if (next.historyIndex != null) "Forward in listening history" else
                    "Chance ${String.format(Locale.ROOT, "%.1f", next.probability * 100)}% · eligible ${next.eligible} · excluded ${next.excluded} · cooling ${next.blocked}")
                if (next.cooldownFallback) Text("Playlist is too short for this cooldown; positive weights remain eligible.")
                if (m.settings.finite) Text("Finite pass: ${m.passCount} identities heard; ${next.remaining} remaining")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = m::smartNext, enabled = ready && next.track != null) { Text("Smart Next") }
                    OutlinedButton(onClick = m::smartPrevious, enabled = ready && m.canPrevious) { Text("Smart Previous") }
                }
                OutlinedButton(onClick = m::reroll, enabled = ready && next.track != null) { Text("Reroll next") }
                Text("AIMP's own Next/headset buttons keep AIMP's order. These companion buttons use the weighted choice.")
                HorizontalDivider()
                Text("Experimental timed automatic mode", style = MaterialTheme.typography.titleMedium)
                Text("Switches about 300 ms BEFORE the estimated end. It can clip endings, miss transitions while the phone sleeps, or differ from AIMP crossfade timing. Headset Next still follows AIMP. No seamless playback guarantee.")
                Row {
                    Switch(checked = m.automatic, onCheckedChange = m::changeAutomatic,
                        enabled = m.automatic || ready)
                    Text("Enable timed shuffle", Modifier.padding(start = 8.dp, top = 12.dp))
                }
                Text(m.automaticStatus)
                Text("CPU protection stays active only during timed playback and its pending command. It allows screen-off switching but uses more battery. Pause, disable and disconnect release it.")
                HorizontalDivider()
                Text("Shuffle settings", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(default, { default = it }, label = { Text("Default weight (0 excludes)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cooldown, { cooldown = it }, label = { Text("Cooldown: other track starts (0–10000)") }, modifier = Modifier.fillMaxWidth())
                Row { Checkbox(finite, { finite = it }); Text("Play each positive-weight identity once per pass", Modifier.padding(top = 12.dp)) }
                Button(onClick = { m.configure(Settings(default.toDoubleOrNull() ?: Double.NaN, cooldown.toIntOrNull() ?: -1, finite)) }, enabled = !m.pending && m.storageError == null) { Text("Apply settings") }
                if (m.settings.finite) OutlinedButton(onClick = m::newPass, enabled = !m.pending) { Text("Start new pass") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = importWeights, enabled = !m.pending) { Text("Import weights") }
                    OutlinedButton(onClick = exportWeights) { Text("Export weights") }
                }
                Text("Weights are saved locally. Android keys include exposed ID + title/artist. Library edits can require reassignment; Windows file paths are not mapped automatically.")
                HorizontalDivider()
                Text("AIMP queue · ${m.tracks.size} entries", style = MaterialTheme.typography.titleMedium)
                if (m.connected && m.tracks.isEmpty()) Text("AIMP exposes no selectable queue. Start music or choose another playlist in AIMP, then refresh.")
                OutlinedTextField(search, { search = it }, label = { Text("Find title or artist") }, modifier = Modifier.fillMaxWidth())
            }
        }
        itemsIndexed(visible) { _, track ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text((if (track.key == m.playback.track?.key) "▶ " else "") + track.title, style = MaterialTheme.typography.titleMedium)
                Text(track.artist)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = track }, enabled = !m.pending && m.storageError == null) { Text("Weight: ${m.weight(track)}") }
                    OutlinedButton(onClick = { m.play(track) }, enabled = ready && track.selectable) { Text("Play") }
                }
                HorizontalDivider()
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                Row {
                    Switch(m.preferences.logging, { m.changePreferences(m.preferences.copy(logging = it)) })
                    Text("Automatic logging", Modifier.padding(start = 8.dp, top = 12.dp))
                }
                Text("Logs persist across restarts; two files of about 2MB are retained. Full export includes saved logs and track identities.")
                m.logError?.let { Text("Log write error: $it") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = copy) { Text("Copy summary") }
                    OutlinedButton(onClick = exportLog) { Text("Export full log") }
                }
                LazyColumn(Modifier.height(240.dp).fillMaxWidth()) {
                    itemsIndexed(m.events.asReversed()) { _, event -> Text(event, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
                }
            }
        }
    }
    editing?.let { track ->
        var text by remember(track.key) { mutableStateOf(m.weight(track).toString()) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(track.title) }, text = {
            Column {
                Text("0 excludes from weighted choices. Fractions and values above 10 are supported. Manual Play still works.")
                OutlinedTextField(text, { text = it }, label = { Text("Weight") })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0, 1, 5, 10).forEach { value -> TextButton(onClick = { text = value.toString() }) { Text("$value") } }
                }
                TextButton(onClick = { m.setWeight(track, "", inherit = true); editing = null }) { Text("Inherit default") }
            }
        }, confirmButton = { TextButton(onClick = { m.setWeight(track, text); editing = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
}

private fun clock(ms: Long): String = if (ms < 0) "unknown" else "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
private fun stateText(state: Int) = when (state) { 3 -> "Playing"; 2 -> "Paused"; 1 -> "Stopped"; 6 -> "Buffering"; else -> "State $state" }
