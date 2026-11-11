package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class PortableSettingsTest {
    private fun fixture() = requireNotNull(javaClass.classLoader!!.getResource("portable-settings.tsv")).readText()
    @Test fun sharedWindowsFixtureAndRoundTrip() {
        val value = PortableSettings.decode(fixture())
        assertEquals(Settings(7.5, 12, true, 0.5), value.rules)
        assertTrue(value.balance); assertEquals(0.5, value.balanceStrength, 0.0)
        assertEquals(SmartWeightOptions(true, 2.0, 0.25, true, false, true, false), value.smart)
        assertEquals(value, PortableSettings.decode(value.encode()))
    }
    @Test fun invalidFilesDoNotProducePartialSettings() {
        listOf("version" to 2.0, "cooldown" to 1.5, "cooldown" to 10001.0, "minimum" to 0.0,
            "finite" to 2.0, "limit" to 0.8, "balanceStrength" to 2.0).forEach { (key, value) ->
            val values = PortableWeights.parse(fixture()).toMutableMap(); values[key] = value
            assertThrows(IllegalArgumentException::class.java) { PortableSettings.decode(PortableWeights.write(values)) }
        }
        val missing = PortableWeights.parse(fixture()).toMutableMap(); missing.remove("smart")
        assertThrows(IllegalArgumentException::class.java) { PortableSettings.decode(PortableWeights.write(missing)) }
    }
    @Test fun optInDoesNotEnterSharedFile() {
        val text = PortableSettings.decode(fixture()).encode()
        assertFalse(text.contains("shareSettings")); assertFalse(text.contains("automatic")); assertFalse(text.contains("theme"))
    }
}
