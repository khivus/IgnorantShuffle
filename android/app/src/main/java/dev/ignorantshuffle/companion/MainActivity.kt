package dev.ignorantshuffle.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
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
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        monitor.log("Notification permission result: $granted"); monitor.refreshNotifications()
    }
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
            ShuffleTheme(monitor.preferences, window) { Surface(Modifier.fillMaxSize()) {
                ShuffleScreen(monitor, ::openSettings, ::openAimp, ::copySummary,
                    { exportText = monitor.report(true); export.launch("IgnorantShuffle-log.txt") },
                    { exportText = monitor.exportWeights(); export.launch("IgnorantShuffle-Android.weights.tsv") },
                    { import.launch(arrayOf("text/*", "application/octet-stream")) }, ::requestNotifications)
            } }
        }
    }
    override fun onResume() { super.onResume(); monitor.log("Activity resumed"); monitor.refresh(); monitor.refreshNotifications() }
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

