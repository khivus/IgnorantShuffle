package dev.ignorantshuffle.companion

import org.json.JSONObject

/** Shared by the atomic file store and JVM migration/persistence tests. */
object PreferenceJson {
    fun decode(text: String): AppPreferences {
        val json = JSONObject(text)
        val stats = json.optJSONObject("stats") ?: JSONObject()
        return AppPreferences(json.optBoolean("logging", true),
            ThemeMode.entries.firstOrNull { it.name == json.optString("theme") } ?: ThemeMode.SYSTEM,
            Accent.entries.firstOrNull { it.name == json.optString("accent") } ?: Accent.ORANGE,
            json.optBoolean("notifyRoll", false), RollStats(stats.optBoolean("artist", true),
                stats.optBoolean("weight", true), stats.optBoolean("chance", true), stats.optBoolean("pool", false),
                stats.optBoolean("cooldown", false), stats.optBoolean("remaining", false), stats.optBoolean("mode", false)),
            RollVisibility.entries.firstOrNull { it.name == json.optString("notificationVisibility") } ?: RollVisibility.ALWAYS,
            json.optLong("leadMs", EndTiming.LEAD_MS).also { require(it in 0..5000) { "Invalid saved switch lead" } },
            json.optBoolean("automatic", true), json.optBoolean("debugPlayback", false))
    }

    fun encode(value: AppPreferences): String {
        val s = value.stats
        require(value.leadMs in 0..5000) { "Switch lead must be between 0 and 5000 ms" }
        return JSONObject().put("logging", value.logging).put("theme", value.theme.name)
            .put("accent", value.accent.name).put("notifyRoll", value.notifyRoll).put("automatic", value.automatic)
            .put("debugPlayback", value.debugPlayback)
            .put("notificationVisibility", value.notificationVisibility.name).put("leadMs", value.leadMs)
            .put("stats", JSONObject().put("artist", s.artist).put("weight", s.weight).put("chance", s.chance)
                .put("pool", s.pool).put("cooldown", s.cooldown).put("remaining", s.remaining).put("mode", s.mode)).toString()
    }
}
