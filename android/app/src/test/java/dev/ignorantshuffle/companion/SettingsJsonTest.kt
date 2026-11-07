package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class SettingsJsonTest {
    @Test fun legacySettingsAdoptMinimumOneWithoutLosingWeightsOrRules() {
        val loaded = SettingsJson.decode("""{"version":1,"default":2.5,"cooldown":10,"finite":true,"weights":{"low":1,"high":25}}""")
        assertEquals(Settings(2.5, 10, true, 1.0), loaded.first)
        assertEquals(mapOf("low" to 1.0, "high" to 25.0), loaded.second)
    }

    @Test fun savedMinimumSurvivesReloadIncludingSmallPositiveFractions() {
        for (minimum in listOf(1e-9, 0.1, 1.0, 2.5, 100.0)) {
            val settings = Settings(5.0, 7, true, minimum)
            assertEquals(settings to emptyMap<String, Double>(), SettingsJson.decode(SettingsJson.encodeSettings(settings)))
        }
    }

    @Test fun invalidSavedMinimumIsRejected() {
        for (minimum in listOf("0", "-0.0", "-1", "\"NaN\"", "\"Infinity\"", "null", "\"invalid\"")) {
            assertThrows(Exception::class.java) {
                SettingsJson.decode("""{"version":1,"default":5,"cooldown":0,"finite":false,"minimumWeight":$minimum,"weights":{}}""")
            }
        }
    }
    @org.junit.Test fun newSettingsStorageContainsRulesOnly() {
        val settings = Settings(defaultWeight = 2.5, minimumWeight = 0.25)
        val text = SettingsJson.encodeSettings(settings)
        assertFalse(org.json.JSONObject(text).has("weights"))
        assertEquals(settings, SettingsJson.decode(text).first)
        assertTrue(SettingsJson.decode(text).second.isEmpty())
    }

}
