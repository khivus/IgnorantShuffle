package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class PlaybackOptionsTest {
    private val sample = TimingSnapshot("a", 3, 20_000, 30_000, 1f, 1000)
    @Test fun zeroLeadWaitsUntilEstimatedEnd() {
        assertEquals(10_000L, EndTiming.delayMillis(sample, 1000, 0))
        assertFalse(EndTiming.due(sample, "a", 10_999, 0))
        assertTrue(EndTiming.due(sample, "a", 11_000, 0))
    }
    @Test fun customLeadAndSpeedAreRespectedByCapturedPlan() {
        val fast = sample.copy(speed = 2f)
        assertEquals(4975L, EndTiming.delayMillis(fast, 1000, 50))
        val roll = ScheduledRoll("a", Choice(null), 4, 5975, 5975, 50)
        assertFalse(HandoffPolicy.valid(roll, fast, 4, 5974))
        assertTrue(HandoffPolicy.valid(roll, fast, 4, 5975))
    }
    @Test fun invalidLeadCannotSchedule() {
        listOf(-1L, 5001L).forEach { assertNull(EndTiming.delayMillis(sample, 1000, it)) }
    }
    @Test fun visibilitySeparatesPausedAndInactivePlayback() {
        (0..11).forEach { state ->
            assertTrue(RollVisibility.ALWAYS.allows(state))
            assertEquals(state == 2 || state == 3, RollVisibility.PLAYING_OR_PAUSED.allows(state))
            assertEquals(state == 3, RollVisibility.PLAYING_ONLY.allows(state))
        }
    }
    @Test fun logFilenameContainsDateTimeAndLogExtension() {
        assertEquals("IgnorantShuffle-2026-10-28_20-30-05.log", LogExport.filename(ZonedDateTime.parse("2026-10-28T20:30:05+03:00")))
    }
}
