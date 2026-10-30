package dev.ignorantshuffle.companion

import org.json.JSONObject

/** The atomic store and migration tests use the same settings format. */
object SettingsJson {
    fun decode(text: String): Pair<Settings, Map<String, Double>> {
        val json = JSONObject(text)
        require(json.getInt("version") == 1) { "Unsupported settings version" }
        val settings = Settings(json.getDouble("default"), json.getInt("cooldown"), json.getBoolean("finite"),
            if (json.has("minimumWeight")) json.getDouble("minimumWeight") else Settings().minimumWeight)
        settings.validate()
        val values = json.getJSONObject("weights")
        val weights = values.keys().asSequence().associateWith { values.getDouble(it) }
        require(weights.values.all { it.isFinite() && it >= 0 }) { "Saved weight is invalid" }
        return settings to weights
    }

    fun encode(settings: Settings, weights: Map<String, Double>): String {
        settings.validate()
        require(weights.values.all { it.isFinite() && it >= 0 }) { "Saved weight is invalid" }
        return JSONObject().put("version", 1).put("default", settings.defaultWeight)
            .put("cooldown", settings.cooldown).put("finite", settings.finite)
            .put("minimumWeight", settings.minimumWeight).put("weights", JSONObject(weights)).toString(2)
    }
}
