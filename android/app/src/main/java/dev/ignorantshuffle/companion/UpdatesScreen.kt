package dev.ignorantshuffle.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Update UI is independent of notification access and the AIMP connection. */
@Composable
internal fun UpdatesScreen(updates: AppUpdates, install: () -> Unit, browser: () -> Unit) {
    var skip by rememberSaveable(updates.release?.tag) { mutableStateOf(false) }
    val release = updates.release
    Dialog(onDismissRequest = { updates.dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("App updates", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { updates.dismiss() }) { Text("Close") }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Text("↑", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(if (release != null) "Update available" else when (updates.phase) {
                        UpdatePhase.CURRENT -> "You're up to date"
                        UpdatePhase.CHECKING -> "Checking for updates"
                        else -> "Keep IgnorantShuffle updated"
                    }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (release != null) Text("App v${release.version}${if (release.preview) " · Preview" else ""}",
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Installed App v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Core v${remember { ShuffleNative.coreVersion() }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(updates.notice, style = MaterialTheme.typography.bodyMedium)
                    if (updates.phase == UpdatePhase.DOWNLOADING) {
                        LinearProgressIndicator(progress = { updates.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("${updates.progress}% downloaded", style = MaterialTheme.typography.labelLarge)
                    } else if (updates.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    updates.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    release?.unavailable?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (release != null) {
                        HorizontalDivider()
                        Text("What's new", style = MaterialTheme.typography.titleLarge)
                        SelectionContainer { ReleaseNotes(release.notes.ifBlank { "No release notes were provided." }) }
                        release.asset?.let { Text("${it.name} · ${String.format(java.util.Locale.ROOT, "%.1f", it.size / 1_000_000.0)} MB",
                            style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = browser) { Text("Open release on GitHub ↗") }
                    }
                    HorizontalDivider()
                    UpdateToggle("Automatic checks", "Check once a day when you open the app", updates.automatic, !updates.busy) {
                        updates.preferences(auto = it)
                    }
                    UpdateToggle("Include preview releases", "Also check Android releases marked as previews", updates.previews, !updates.busy) {
                        updates.preferences(preview = it)
                    }
                    OutlinedButton(onClick = { updates.check(true) }, enabled = !updates.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (updates.phase == UpdatePhase.ERROR) "Retry check" else "Check for updates")
                    }
                    if (release == null) TextButton(onClick = browser) { Text("View releases on GitHub ↗") }
                }
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (release != null && !updates.busy && updates.downloaded == null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(skip, { skip = it })
                        Text("Skip this version", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (updates.busy) OutlinedButton(onClick = updates::cancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    else if (release != null) Button(onClick = { if (updates.downloaded != null) install() else updates.download() },
                        enabled = updates.downloaded != null || release.asset != null, modifier = Modifier.fillMaxWidth()) {
                        Text(if (updates.downloaded != null) "Install update" else if (updates.error != null) "Retry download" else "Download update")
                    }
                    TextButton(onClick = { updates.dismiss(skip) }, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
                }
            }
        }
    }
}

@Composable
private fun UpdateToggle(title: String, description: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, change, enabled = enabled)
    }
}

@Composable
private fun ReleaseNotes(notes: String) {
    // Small text-only Markdown presentation; release content is never executed in a WebView.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notes.lines().filter(String::isNotBlank).take(300).forEach { raw ->
            val line = raw.trim()
            val heading = line.startsWith('#')
            val text = when { heading -> line.trimStart('#', ' '); line.startsWith("- ") || line.startsWith("* ") -> "• ${line.drop(2)}"; else -> line }
            Text(buildAnnotatedString {
                var end = 0
                Regex("\\*\\*(.+?)\\*\\*").findAll(text).forEach { match ->
                    append(text.substring(end, match.range.first))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
                    end = match.range.last + 1
                }
                append(text.substring(end))
            }, style = if (heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        }
    }
}
