package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class SettingsJsonTest {
    @Test fun legacySettingsAdoptMinimumOneWithoutLosingWeightsOrRules() {
        val loaded = SettingsJson.decode("""{"version":1,"default":2.5,"cooldown":10,"finite":true,"weights":{"low":1,"high":25}}""")
        assertEquals(Settings(2.5, 10, true, 1.0), loaded.first)
        assertEquals(mapOf("low" to 1.0, "high" to 25.0), loaded.second)
    }

    @Test fun savedMinimumSurvivesReloadIncludingExplicitZeroAndFractions() {
        val weights = mapOf("excluded" to 0.0, "low" to 1.0, "high" to 25.5)
        for (minimum in listOf(0.0, 1.0, 2.5, 100.0)) {
            val settings = Settings(5.0, 7, true, minimum)
            assertEquals(settings to weights, SettingsJson.decode(SettingsJson.encode(settings, weights)))
        }
    }

    @Test fun invalidSavedMinimumIsRejected() {
        for (minimum in listOf("-1", "\"NaN\"", "\"Infinity\"", "null", "\"invalid\"")) {
            assertThrows(Exception::class.java) {
                SettingsJson.decode("""{"version":1,"default":5,"cooldown":0,"finite":false,"minimumWeight":$minimum,"weights":{}}""")
            }
        }
    }
}
