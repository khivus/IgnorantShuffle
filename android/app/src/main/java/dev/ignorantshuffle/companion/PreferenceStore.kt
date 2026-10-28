package dev.ignorantshuffle.companion

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

class PreferenceStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "app-preferences.json"))
    fun load(): AppPreferences {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return AppPreferences()
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val stats = json.optJSONObject("stats") ?: JSONObject()
        return AppPreferences(json.optBoolean("logging", true),
            ThemeMode.entries.firstOrNull { it.name == json.optString("theme") } ?: ThemeMode.SYSTEM,
            Accent.entries.firstOrNull { it.name == json.optString("accent") } ?: Accent.ORANGE,
            json.optBoolean("notifyRoll", false), RollStats(stats.optBoolean("artist", true),
                stats.optBoolean("weight", true), stats.optBoolean("chance", true), stats.optBoolean("pool", false),
                stats.optBoolean("cooldown", false), stats.optBoolean("remaining", false), stats.optBoolean("mode", false)),
            RollVisibility.entries.firstOrNull { it.name == json.optString("notificationVisibility") } ?: RollVisibility.ALWAYS,
            json.optLong("leadMs", EndTiming.LEAD_MS).also { require(it in 0..5000) { "Invalid saved switch lead" } })
    }
    fun save(value: AppPreferences) {
        val s = value.stats
        require(value.leadMs in 0..5000) { "Switch lead must be between 0 and 5000 ms" }
        val json = JSONObject().put("logging", value.logging).put("theme", value.theme.name)
            .put("accent", value.accent.name).put("notifyRoll", value.notifyRoll)
            .put("notificationVisibility", value.notificationVisibility.name).put("leadMs", value.leadMs)
            .put("stats", JSONObject().put("artist", s.artist).put("weight", s.weight).put("chance", s.chance)
                .put("pool", s.pool).put("cooldown", s.cooldown).put("remaining", s.remaining).put("mode", s.mode))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
