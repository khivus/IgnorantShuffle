package dev.ignorantshuffle.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
            .setType(if (exportKind == "log") "application/octet-stream" else "text/tab-separated-values")
    }) { uri ->
        if (uri != null) {
            // The document picker can recreate this Activity. Rebuild the text
            // from app state rather than keeping a transient multi-MB string.
            val text = if (exportKind == "weights") monitor.exportWeights() else monitor.report(true)
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
        exportKind = savedInstanceState?.getString("exportKind") ?: "log"
        installAfterPermission = savedInstanceState?.getBoolean("installAfterPermission") ?: false
        enableEdgeToEdge()
        setContent {
            ShuffleTheme(monitor.preferences, window) { Surface(Modifier.fillMaxSize()) {
                ShuffleScreen(monitor, ::openSettings, ::openAimp, ::copySummary,
                    { exportKind = "log"; export.launch(LogExport.filename()) },
                    { exportKind = "weights"; export.launch("IgnorantShuffle-Android.weights.tsv") },
                    { import.launch(arrayOf("text/*", "application/octet-stream")) }, ::requestNotifications, ::chooseMusicFolder, updates)
                if (updates.visible) UpdatesScreen(updates, updates::prepareInstall, ::openUpdateRelease)
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
    override fun onResume() { super.onResume(); monitor.log("Activity resumed"); monitor.refresh(); monitor.refreshNotifications(); monitor.reloadSharedWeights(); updates.onForeground() }
    override fun onStop() { monitor.log("Activity backgrounded"); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportKind", exportKind)
        outState.putBoolean("installAfterPermission", installAfterPermission)
        super.onSaveInstanceState(outState)
    }
    private fun openSettings() { runCatching { startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { monitor.log("Settings error: $it") } }
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
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("IgnorantShuffle", monitor.report().take(200_000)))
        monitor.log("Summary copied")
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
