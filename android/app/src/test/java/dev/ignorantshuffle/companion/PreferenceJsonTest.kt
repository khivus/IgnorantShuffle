package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class PreferenceJsonTest {
    @Test fun correctionDelayMigratesAndPersistsIndependentlyOfTrackEndLead() {
        val old = PreferenceJson.decode("""{"reactivePlayback":true,"leadMs":150}""")
        assertTrue(old.reactivePlayback)
        assertEquals(200L, old.correctionDelayMs)
        assertEquals(150L, old.leadMs)
        for (delay in listOf(0L, 25L, 200L, 2000L)) {
            val choice = old.copy(correctionDelayMs = delay)
            assertEquals(choice, PreferenceJson.decode(PreferenceJson.encode(choice)))
        }
    }

    @Test fun invalidCorrectionDelaysCannotBeSavedOrLoaded() {
        for (delay in listOf(-1L, 2001L)) {
            assertThrows(IllegalArgumentException::class.java) {
                PreferenceJson.encode(AppPreferences(correctionDelayMs = delay))
            }
            assertThrows(IllegalArgumentException::class.java) {
                PreferenceJson.decode("""{"correctionDelayMs":$delay}""")
            }
        }
    }
    @Test fun reactiveModeIsOptInAndPersistsWithoutChangingOtherPreferences() {
        assertFalse(AppPreferences().reactivePlayback)
        assertFalse(PreferenceJson.decode("{}").reactivePlayback)
        for (enabled in listOf(false, true)) {
            val choice = AppPreferences(reactivePlayback = enabled, automatic = false, debugPlayback = true,
                leadMs = 50, theme = ThemeMode.DARK)
            assertEquals(choice, PreferenceJson.decode(PreferenceJson.encode(choice)))
        }
    }
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

    @Test fun notificationButtonsMigrateAndPersistIndependently() {
        val legacy = PreferenceJson.decode("""{"notifyRoll":true,"lockScreenControls":false}""")
        assertTrue(legacy.notificationNext)
        assertTrue(legacy.notificationPrevious)
        assertFalse(PreferenceJson.encode(legacy).contains("lockScreenControls"))
        for (next in listOf(false, true)) for (previous in listOf(false, true)) {
            val choice = AppPreferences(notifyRoll = true, notificationNext = next, notificationPrevious = previous)
            assertEquals(choice, PreferenceJson.decode(PreferenceJson.encode(choice)))
            assertEquals(NotificationButtons(next, previous), NotificationButtons.create(choice, false, true, true))
            assertEquals(NotificationButtons(false, false), NotificationButtons.create(choice, true, true, true))
            assertEquals(NotificationButtons(false, false), NotificationButtons.create(choice, false, false, false))
            assertEquals(NotificationButtons(false, false), NotificationButtons.create(choice.copy(notifyRoll = false), false, true, true))
            assertEquals(NotificationButtons(false, previous), NotificationButtons.create(choice, false, false, true))
        }
    }
}
