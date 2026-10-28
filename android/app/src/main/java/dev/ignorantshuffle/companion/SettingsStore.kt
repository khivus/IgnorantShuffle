package dev.ignorantshuffle.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class SettingsStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "weights-settings.json"))

    fun load(): Pair<Settings, Map<String, Double>> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Settings() to emptyMap()
        val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(json.getInt("version") == 1) { "Unsupported settings version" }
        val settings = Settings(json.getDouble("default"), json.getInt("cooldown"), json.getBoolean("finite"))
        settings.validate()
        val values = json.getJSONObject("weights")
        val weights = values.keys().asSequence().associateWith { values.getDouble(it) }
        require(weights.values.all { it.isFinite() && it >= 0 }) { "Saved weight is invalid" }
        return settings to weights
    }

    fun save(settings: Settings, weights: Map<String, Double>) {
        settings.validate()
        require(weights.values.all { it.isFinite() && it >= 0 })
        val json = JSONObject().put("version", 1).put("default", settings.defaultWeight)
            .put("cooldown", settings.cooldown).put("finite", settings.finite).put("weights", JSONObject(weights))
        val stream = file.startWrite()
        try {
            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
