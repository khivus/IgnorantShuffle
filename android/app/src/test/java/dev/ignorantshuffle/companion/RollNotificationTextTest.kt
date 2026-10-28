package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class RollNotificationTextTest {
    private val choice = Choice(Track(1, "id", null, "Song", "Artist"), .25, 4, 2, 1, true, 3)
    @Test fun selectedStatsAppear() {
        val text = RollNotificationText.create(choice, 25.0, Settings(finite = true), true,
            RollStats(pool = true, cooldown = true, remaining = true, mode = true), 7)!!
        listOf("Artist", "25.0", "25.0%", "Eligible: 4", "Cooling: 1", "Remaining this pass: 3", "Timed", "Finite").forEach {
            assertTrue("Missing $it", text.body.contains(it))
        }
    }
    @Test fun hiddenStatsAndPrivateIdsDoNotLeakIntoText() {
        val text = RollNotificationText.create(choice, 25.0, Settings(), false,
            RollStats(false, false, false, false, false, false, false), 8)!!
        assertEquals("Next: Song", text.title)
        assertFalse(text.body.contains("Artist")); assertFalse(text.body.contains("Weight")); assertFalse(text.body.contains("android:v1"))
    }
    @Test fun noEligibleChoiceProducesNoNotification() {
        assertNull(RollNotificationText.create(Choice(null), 0.0, Settings(), false, RollStats(), 1))
    }
    @Test fun historyDoesNotInventAProbability() {
        val text = RollNotificationText.create(choice.copy(historyIndex = 1), 5.0, Settings(), false, RollStats(), 1)!!
        assertTrue(text.body.contains("Listening history")); assertFalse(text.body.contains("%"))
    }
}
