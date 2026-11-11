package dev.ignorantshuffle.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    private val monitor get() = (application as ShuffleApplication).controller
    private val updates get() = (application as ShuffleApplication).updates
    private var exportKind = "log"
    private var installAfterPermission = false
    private var batteryExempt by mutableStateOf(false)
    private var showBatteryPrompt by mutableStateOf(false)
    private val installAccess = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (installAfterPermission) {
            if (packageManager.canRequestPackageInstalls()) {
                if (!updates.busy) { installAfterPermission = false; updates.prepareInstall() }
            } else {
                installAfterPermission = false
                updates.installProblem("Installation permission was not granted. Tap Install update to try again")
            }
        }
    }
    private val musicFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { reply ->
        val uri = reply.data?.data
        if (reply.resultCode == RESULT_OK && uri != null) {
            try {
                val requested = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val granted = (reply.data?.flags ?: 0) and requested
                check(granted == requested) { "Choose a music folder with read and write access" }
                contentResolver.takePersistableUriPermission(uri, granted)
                monitor.chooseMusicFolder(uri)
            } catch (e: Exception) { monitor.folder.pickerFailed(e) }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        monitor.log("Notification permission result: $granted"); monitor.refreshNotifications()
    }
    private val export = registerForActivityResult(object : ActivityResultContracts.CreateDocument("text/plain") {
        override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input)
            // The default MIME preserves the supplied .log extension; text/plain appends .txt.
            .setType(when(exportKind) { "log" -> "application/octet-stream"; "json" -> "application/json"; "csv" -> "text/csv"; else -> "text/tab-separated-values" })
            .apply { if (exportKind == "json" || exportKind == "csv") putExtra(Intent.EXTRA_LOCAL_ONLY, true) }
    }) { uri ->
        if (uri != null) {
            // The document picker can recreate this Activity. Rebuild the text
            // from app state rather than keeping a transient multi-MB string.
            val write: (String) -> Unit = { text ->
                requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            }
            if (exportKind == "weights") monitor.exportWeights(write)
            else if (exportKind == "json" || exportKind == "csv") monitor.statistics.export(exportKind, write)
            else Thread {
                try { write(monitor.report(true)); runOnUiThread { monitor.log("Export completed"); monitor.acknowledge("Log exported") } }
                catch (e: Exception) { runOnUiThread { monitor.log("Export error: $e"); monitor.acknowledge("Export failed: ${e.message}") } }
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
                        require(data.size() + n <= 8_000_000) { "Import is too large (maximum 8MB)" }
                        data.write(buffer, 0, n)
                    }
                }
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data.toByteArray())).toString()
                runOnUiThread { monitor.importWeights(text) }
            } catch (e: Exception) { runOnUiThread { monitor.log("Import error: $e"); monitor.acknowledge("Import failed: ${e.message}") } }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportKind = savedInstanceState?.getString("exportKind") ?: "log"
        installAfterPermission = savedInstanceState?.getBoolean("installAfterPermission") ?: false
        enableEdgeToEdge()
        setContent {
            ShuffleTheme(monitor.preferences, window) { Surface(Modifier.fillMaxSize()) {
                ShuffleScreen(monitor, ::openSettings, ::openAimp, ::copySummary,
                    { exportKind = "log"; export.launch(LogExport.filename()) },
                    { exportKind = "weights"; export.launch(PortableWeights.FILENAME) },
                    { import.launch(arrayOf("text/*", "application/octet-stream")) }, ::requestNotifications, ::chooseMusicFolder,
                    batteryExempt, ::openBatterySettings, updates,
                    { format -> exportKind = format; export.launch("ignorant-shuffle-statistics.$format") })
                if (updates.visible) UpdatesScreen(updates, updates::prepareInstall, ::openUpdateRelease)
                if (showBatteryPrompt && !updates.visible) AlertDialog(
                    onDismissRequest = ::dismissBatteryPrompt,
                    title = { Text("Keep shuffle working in the background") },
                    text = { Text("Android battery optimization can delay IgnorantShuffle and make AIMP skip the next weighted choice when the app is in the background or the screen is off.\n\nAllow IgnorantShuffle to run without battery optimization. This can use more battery during timed playback. You can change this later under Player > Background playback.") },
                    confirmButton = { TextButton(onClick = { dismissBatteryPrompt(); openBatterySettings() }) { Text("Disable optimization") } },
                    dismissButton = { TextButton(onClick = ::dismissBatteryPrompt) { Text("Not now") } })
                LaunchedEffect(updates.installRequest) {
                    updates.takeInstallRequest()?.let { file ->
                        if (!packageManager.canRequestPackageInstalls()) {
                            updates.installProblem("Allow installs from IgnorantShuffle in Android settings, then return")
                            installAfterPermission = true
                            runCatching { installAccess.launch(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                                .onFailure { installAfterPermission = false; updates.installProblem("Cannot open installation settings: ${it.message}") }
                        } else runCatching {
                            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.updates", file)
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Android update", uri) })
                            updates.installerOpened()
                        }.onFailure { updates.installProblem("Cannot open Android's installer: ${it.message}") }
                    }
                }
                LaunchedEffect(updates.phase) {
                    if (installAfterPermission && updates.phase == UpdatePhase.READY && packageManager.canRequestPackageInstalls()) {
                        installAfterPermission = false; updates.prepareInstall()
                    }
                }
            } }
        }
    }
    override fun onResume() {
        super.onResume()
        batteryExempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        showBatteryPrompt = !batteryExempt && !getPreferences(MODE_PRIVATE).getBoolean("batterySetupShown", false)
        monitor.log("Activity resumed; batteryExempt=$batteryExempt")
        monitor.refresh(); monitor.refreshNotifications(); monitor.reloadSharedWeights(); updates.onForeground()
    }
    override fun onStop() { monitor.log("Activity backgrounded");monitor.statistics.flushShared(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportKind", exportKind)
        outState.putBoolean("installAfterPermission", installAfterPermission)
        super.onSaveInstanceState(outState)
    }
    private fun openSettings() { runCatching { startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { monitor.log("Settings error: $it") } }
    private fun dismissBatteryPrompt() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("batterySetupShown", true).apply()
        showBatteryPrompt = false
    }
    private fun openBatterySettings() {
        val exempt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryExempt = exempt
        val intents = buildList {
            if (!exempt) add(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            add(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.onFailure { monitor.log("Battery settings unavailable (${intent.action}): $it") }.isSuccess) return
        }
        monitor.log("Open Android Settings > Apps > IgnorantShuffle > Battery and allow unrestricted background use")
    }
    private fun openUpdateRelease() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updates.release?.pageUrl ?: AndroidReleases.releasesUrl))) }
            .onFailure { updates.installProblem("Cannot open release page: ${it.message}") }
    }
    private fun chooseMusicFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        runCatching { musicFolder.launch(intent) }.onFailure { monitor.folder.pickerFailed(it) }
    }
    private fun openAimp() {
        val intent = packageManager.getLaunchIntentForPackage("com.aimp.player")
        if (intent == null) monitor.log("AIMP is not installed or has no launcher activity")
        else runCatching { startActivity(intent) }.onFailure { monitor.log("AIMP launch error: $it") }
    }
    private fun copySummary() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ignorant Shuffle", monitor.report().take(200_000)))
        monitor.log("Summary copied"); monitor.acknowledge("Summary copied")
    }
    private fun requestNotifications() {
        val history = getPreferences(MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!history.getBoolean("notificationsAsked", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            history.edit().putBoolean("notificationsAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else runCatching {
            startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
        }.onFailure { monitor.log("Cannot open notification settings: $it") }
    }
}
