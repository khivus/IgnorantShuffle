package dev.ignorantshuffle.companion

import android.content.Context
import android.util.AtomicFile
import java.io.File

class SettingsStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "weights-settings.json"))

    fun load(): Pair<Settings, Map<String, Double>> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Settings() to emptyMap()
        return SettingsJson.decode(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    fun save(settings: Settings, weights: Map<String, Double>) {
        val json = SettingsJson.encode(settings, weights)
        val stream = file.startWrite()
        try {
            stream.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
