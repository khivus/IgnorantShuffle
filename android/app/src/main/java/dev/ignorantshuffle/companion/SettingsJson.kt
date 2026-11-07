package dev.ignorantshuffle.companion

import org.json.JSONObject

/** The atomic store and migration tests use the same settings format. */
object SettingsJson {
    fun decode(text: String): Pair<Settings, Map<String, Double>> {
        val json = JSONObject(text)
        require(json.getInt("version") in 1..2) { "Unsupported settings version" }
        val settings = Settings(json.getDouble("default"), json.getInt("cooldown"), json.getBoolean("finite"),
            if (json.has("minimumWeight")) json.getDouble("minimumWeight") else Settings().minimumWeight)
        settings.validate()
        val values = json.optJSONObject("weights") ?: JSONObject()
        val weights = values.keys().asSequence().associateWith { values.getDouble(it) }
        require(weights.values.all { it.isFinite() && it >= 0 }) { "Saved weight is invalid" }
        return settings to weights
    }

    fun encodeSettings(settings: Settings): String {
        settings.validate()
        return JSONObject().put("version", 2).put("default", settings.defaultWeight)
            .put("cooldown", settings.cooldown).put("finite", settings.finite)
            .put("minimumWeight", settings.minimumWeight).toString(2)
    }

}
