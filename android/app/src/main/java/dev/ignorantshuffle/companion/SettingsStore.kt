package dev.ignorantshuffle.companion

import android.content.Context
import android.util.AtomicFile
import java.io.File

class SettingsStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "shuffle-settings.json"))

    fun load(): Pair<Settings, Map<String, Double>> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            val legacy = AtomicFile(File(context.filesDir, "weights-settings.json"))
            if (!legacy.baseFile.exists() && !File(legacy.baseFile.path + ".bak").exists()) return Settings() to emptyMap()
            return SettingsJson.decode(legacy.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }).first to emptyMap()
        }
        return SettingsJson.decode(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun save(settings: Settings) {
        val json = SettingsJson.encodeSettings(settings)
        val stream = file.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
