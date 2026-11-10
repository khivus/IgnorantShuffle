package dev.ignorantshuffle.companion

import org.json.JSONObject

/** Shared by the atomic file store and JVM migration/persistence tests. */
object PreferenceJson {
    fun decode(text: String): AppPreferences {
        val json = JSONObject(text)
        val stats = json.optJSONObject("stats") ?: JSONObject()
        val smart = json.optJSONObject("smartWeights") ?: JSONObject()
        return AppPreferences(json.optBoolean("logging", true),
            ThemeMode.entries.firstOrNull { it.name == json.optString("theme") } ?: ThemeMode.SYSTEM,
            Accent.entries.firstOrNull { it.name == json.optString("accent") } ?: Accent.ORANGE,
            json.optBoolean("notifyRoll", false), RollStats(stats.optBoolean("artist", true),
                stats.optBoolean("weight", true), stats.optBoolean("chance", true), stats.optBoolean("pool", false),
                stats.optBoolean("cooldown", false), stats.optBoolean("remaining", false), stats.optBoolean("mode", false)),
            RollVisibility.entries.firstOrNull { it.name == json.optString("notificationVisibility") } ?: RollVisibility.ALWAYS,
            json.optLong("leadMs", EndTiming.LEAD_MS).also { require(it in 0..5000) { "Invalid saved switch lead" } },
            json.optBoolean("automatic", true), json.optBoolean("debugPlayback", false),
            json.optBoolean("reactivePlayback", false),
            json.optLong("correctionDelayMs", ReactivePlayback.SETTLE_MS).also {
                require(it in 0..ReactivePlayback.MAX_DELAY_MS) { "Invalid saved correction delay" }
            }, json.optBoolean("notificationNext", true), json.optBoolean("notificationPrevious", true), json.optBoolean("balanceListening", false),
            json.optDouble("balanceStrength",1.0).also { require(it.isFinite() && it in 0.25..1.0) { "Invalid balance strength" } },
            SmartWeightOptions(smart.optBoolean("enabled",false),smart.optDouble("strength",1.0),smart.optDouble("limit",0.15),
                smart.optBoolean("skips",true),smart.optBoolean("completions",true),smart.optBoolean("replays",true),smart.optBoolean("volume",true)).also { it.validate() })
    }

    fun encode(value: AppPreferences): String {
        val s = value.stats
        val learning=value.smartWeights.also { it.validate() }
        require(value.balanceStrength.isFinite() && value.balanceStrength in 0.25..1.0) { "Balance strength must be 0.25–1" }
        require(value.leadMs in 0..5000) { "Switch lead must be between 0 and 5000 ms" }
        require(value.correctionDelayMs in 0..ReactivePlayback.MAX_DELAY_MS) { "Correction delay must be between 0 and 2000 ms" }
        return JSONObject().put("logging", value.logging).put("theme", value.theme.name)
            .put("accent", value.accent.name).put("notifyRoll", value.notifyRoll).put("automatic", value.automatic)
            .put("debugPlayback", value.debugPlayback)
            .put("balanceListening", value.balanceListening)
            .put("balanceStrength", value.balanceStrength)
            .put("smartWeights",JSONObject().put("enabled",learning.enabled).put("strength",learning.strength).put("limit",learning.limit)
                .put("skips",learning.skips).put("completions",learning.completions).put("replays",learning.replays).put("volume",learning.volume))
            .put("reactivePlayback", value.reactivePlayback)
            .put("correctionDelayMs", value.correctionDelayMs)
            .put("notificationNext", value.notificationNext).put("notificationPrevious", value.notificationPrevious)
            .put("notificationVisibility", value.notificationVisibility.name).put("leadMs", value.leadMs)
            .put("stats", JSONObject().put("artist", s.artist).put("weight", s.weight).put("chance", s.chance)
                .put("pool", s.pool).put("cooldown", s.cooldown).put("remaining", s.remaining).put("mode", s.mode)).toString()
    }
}
