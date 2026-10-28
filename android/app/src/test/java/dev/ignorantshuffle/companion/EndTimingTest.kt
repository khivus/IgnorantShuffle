package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class EndTimingTest {
    private val sample = TimingSnapshot("a", 3, 20_000, 30_000, 1f, 1000)
    @Test fun deadlineProjectsPositionWithoutPolling() { assertEquals(9700L, EndTiming.delayMillis(sample, 1000)) }
    @Test fun elapsedTimeAndPlaybackSpeedAreApplied() {
        assertEquals(8700L, EndTiming.delayMillis(sample, 2000))
        assertEquals(4850L, EndTiming.delayMillis(sample.copy(speed = 2f), 1000))
    }
    @Test fun pauseStopBufferingCannotTrigger() {
        listOf(0, 1, 2, 6, 7).forEach { assertNull(EndTiming.delayMillis(sample.copy(state = it), 1000)) }
    }
    @Test fun unknownAndInvalidTimingCannotTrigger() {
        listOf(sample.copy(key = null), sample.copy(position = -1), sample.copy(duration = -1),
            sample.copy(duration = 100), sample.copy(speed = 0f), sample.copy(speed = Float.NaN),
            sample.copy(updatedAt = 0), sample.copy(updatedAt = 2000)).forEach {
            assertNull(EndTiming.delayMillis(it, 1000))
        }
    }
    @Test fun changedSourceCannotBeTakenOverAtOldDeadline() {
        assertFalse(EndTiming.due(sample.copy(key = "manual-selection"), "a", 10_700))
    }
    @Test fun lateSuspendedHandlerCannotTakeOverAmbiguousPlayback() {
        assertNull(EndTiming.delayMillis(sample, 15_000))
        assertFalse(EndTiming.due(sample, "a", 15_000))
    }
    @Test fun seekingBackRequiresARescheduledDeadline() {
        assertFalse(EndTiming.due(sample.copy(position = 0, updatedAt = 10_700), "a", 10_700))
    }
    @Test fun thresholdNeverFiresBeforeLeadBoundary() {
        assertFalse(EndTiming.due(sample, "a", 10_699))
        assertTrue(EndTiming.due(sample, "a", 10_700))
    }
}
