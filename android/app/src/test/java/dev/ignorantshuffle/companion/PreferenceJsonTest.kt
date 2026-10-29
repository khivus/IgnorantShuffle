package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class PreferenceJsonTest {
    @Test fun newAndLegacyInstallsEnableAutomaticWithoutLosingOldSettings() {
        assertTrue(AppPreferences().automatic)
        val old = PreferenceJson.decode("""{"theme":"DARK","accent":"BLUE","leadMs":100,"logging":false}""")
        assertTrue(old.automatic)
        assertEquals(ThemeMode.DARK, old.theme)
        assertEquals(Accent.BLUE, old.accent)
        assertEquals(100L, old.leadMs)
        assertFalse(old.logging)
    }

    @Test fun bothAutomaticChoicesSurviveSavingAndReloading() {
        for (enabled in listOf(false, true)) {
            val choice = AppPreferences(automatic = enabled, notifyRoll = true, leadMs = 50,
                stats = RollStats(pool = true), notificationVisibility = RollVisibility.PLAYING_ONLY)
            val loaded = PreferenceJson.decode(PreferenceJson.encode(choice))
            assertEquals(choice, loaded)
            assertEquals(choice, PreferenceJson.decode(PreferenceJson.encode(loaded.copy(theme = ThemeMode.DARK))).copy(theme = choice.theme))
        }
    }

    @Test fun explicitOffDoesNotFallBackToNewDefault() {
        assertFalse(PreferenceJson.decode("""{"automatic":false}""").automatic)
        assertTrue(PreferenceJson.decode("{}").automatic)
    }
    @Test fun debugModeIsOptInAndPersistsWithOtherSettings() {
        assertFalse(PreferenceJson.decode("{}").debugPlayback)
        val enabled = AppPreferences(debugPlayback = true, logging = false)
        assertEquals(enabled, PreferenceJson.decode(PreferenceJson.encode(enabled)))
    }
}
