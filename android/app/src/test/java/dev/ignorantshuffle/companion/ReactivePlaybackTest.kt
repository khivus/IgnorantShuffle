package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class ReactivePlaybackTest {
    private val a = Track(1, "a", null, "A", "Artist")
    private val b = Track(2, "b", null, "B", "Artist")
    private val c = Track(3, "c", null, "C", "Artist")
    private val d = Track(4, "d", null, "D", "Artist")
    private fun playing(track: Track) = Playback(state = 3, track = track, title = track.title)
    private fun armed(choice: Choice = Choice(b)) = ReactivePlayback().apply { arm(a.key, choice, 7) }

    @Test fun zeroDelayCorrectsOnFirstIdentifiedPlayingObservation() {
        val p = armed()
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c).copy(state = 2), 1000, 0))
        val result = p.observe(playing(c), 1070) as ReactiveDecision.Correct
        assertEquals(b, result.roll.choice.track)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 1071, 0))
    }

    @Test fun shortCustomDelaysAreNotRoundedToOneHundredMilliseconds() {
        for (delay in listOf(1L, 25L, 50L, 2000L)) {
            val p = armed()
            assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 1000, delay))
            assertEquals(delay.coerceAtMost(100L), p.nextCheckDelayMillis(1000, delay))
            assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 1000 + delay - 1, delay))
            assertEquals(1L, p.nextCheckDelayMillis(1000 + delay - 1, delay))
            assertTrue(p.observe(playing(c), 1000 + delay, delay) is ReactiveDecision.Correct)
        }
    }

    @Test fun customDelayRestartsForAnotherUnexpectedTrack() {
        val p = armed()
        p.observe(playing(c), 1000, 25)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(d), 1020, 25))
        assertEquals(25L, p.nextCheckDelayMillis(1020, 25))
        assertEquals(ReactiveDecision.Wait, p.observe(playing(d), 1044, 25))
        assertEquals(d.key, (p.observe(playing(d), 1045, 25) as ReactiveDecision.Correct).rejected)
    }

    @Test fun combinedModeKeepsNaturalEndTimerAndBlocksItDuringEarlySkipCorrection() {
        val p = armed()
        val source = playing(a).copy(position = 1000, duration = 10_000, speed = 1f, updatedAt = 1000)
        val sample = TimingSnapshot(a.key, 3, 1000, 10_000, 1f, 1000)
        val plan = ScheduledRoll(a.key, Choice(b), 7, 9850, 9850)
        assertTrue(p.timedPlaybackReady(source, a.key))
        assertEquals(8850L, EndTiming.delayMillis(sample, 1000))
        assertTrue(HandoffPolicy.valid(plan, sample, 7, 9850))
        assertEquals(ReactiveDecision.Accept(), p.observe(source, 1000, 25))

        assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 5000, 25))
        assertFalse(p.timedPlaybackReady(playing(c), a.key))
        assertFalse(HandoffPolicy.valid(plan, sample.copy(key = c.key), 7, 9850))
        val correction = p.observe(playing(c), 5025, 25) as ReactiveDecision.Correct
        assertEquals(plan.choice, correction.roll.choice)
        assertFalse(p.timedPlaybackReady(source, a.key))
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(b), 5100, 25))
        p.arm(b.key, Choice(d), 8)
        assertTrue(p.timedPlaybackReady(playing(b), b.key))
        assertFalse(p.timedPlaybackReady(playing(c), b.key))
        assertEquals(ReactiveDecision.Accept(manualOverride = true), p.observe(playing(c), 5200, 25))
    }

    @Test fun combinedTimerWaitsThroughTransientPauseAndResumesOnSource() {
        val p = armed()
        val paused = playing(a).copy(state = 2)
        p.observe(paused, 1000, 25)
        assertFalse(p.timedPlaybackReady(paused, a.key))
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(a), 1070, 25))
        assertTrue(p.timedPlaybackReady(playing(a), a.key))
    }
    private fun correct(p: ReactivePlayback, track: Track = c, at: Long = 1000): ReactiveDecision.Correct {
        assertEquals(ReactiveDecision.Wait, p.observe(playing(track), at, 200))
        return p.observe(playing(track), at + 200, 200) as ReactiveDecision.Correct
    }

    @Test fun startupSourceAndExpectedTargetNeedNoCommands() {
        val p = ReactivePlayback()
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(c), 0, 200))
        p.arm(a.key, Choice(b), 7)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(a), 1, 200))
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(b), 2, 200))
    }

    @Test fun capturesOriginalChoiceAndSequenceBeforeUnexpectedPlayback() {
        val choice = Choice(b, probability = 0.4, historyIndex = 2)
        val p = armed(choice)
        val correction = correct(p)
        assertEquals(ReactiveRoll(a.key, choice, 7), correction.roll)
        assertEquals(c.key, correction.rejected)
        assertTrue(p.correcting)
    }

    @Test fun correctionCanReturnToSourceWhenItIsTheWeightedTarget() {
        val p = armed(Choice(a))
        assertEquals(a, correct(p).roll.choice.track)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(a), 1300, 200))
        p.arm(a.key, Choice(b), 8)
        assertEquals(ReactiveDecision.Accept(manualOverride = true), p.observe(playing(c), 1400, 200))
    }

    @Test fun repeatedCallbacksDoNotBecomeManualOverrideOrSendAnotherCorrection() {
        val p = armed()
        correct(p)
        for (now in listOf(1201L, 1500L, 8000L)) {
            assertEquals(ReactiveDecision.Wait, p.observe(playing(c), now, 200))
        }
        p.arm(c.key, Choice(d), 8) // A pending correction cannot be replaced by observations.
        assertTrue(p.correcting)
    }

    @Test fun secondSelectionAfterConfirmedCorrectionIsAllowedAndConsumedOnce() {
        val p = armed()
        correct(p)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(b), 1300, 200))
        p.arm(b.key, Choice(d), 8)
        assertEquals(ReactiveDecision.Accept(manualOverride = true), p.observe(playing(c), 1400, 200))
        p.arm(c.key, Choice(d), 9)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(c), 1500, 200))
        p.arm(b.key, Choice(d), 10)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 1600, 200))
    }

    @Test fun overrideExpiresFiveSecondsAfterConfirmation() {
        val p = armed()
        correct(p)
        p.observe(playing(b), 1300, 200)
        p.arm(b.key, Choice(d), 8)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 6301, 200))
    }

    @Test fun anotherTrackDoesNotInheritRejectedTrackOverride() {
        val p = armed()
        correct(p)
        p.observe(playing(b), 1300, 200)
        p.arm(b.key, Choice(a), 8)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(d), 1400, 200))
        assertTrue(p.observe(playing(d), 1600, 200) is ReactiveDecision.Correct)
    }

    @Test fun staleMetadataReturningToSourceCancelsCandidate() {
        val p = armed()
        p.observe(playing(c), 1000, 200)
        p.observe(playing(a), 1100, 200)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 1300, 200))
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 1499, 200))
        assertTrue(p.observe(playing(c), 1500, 200) is ReactiveDecision.Correct)
    }

    @Test fun changingUnexpectedIdentityRestartsSettling() {
        val p = armed()
        p.observe(playing(c), 1000, 200)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(d), 1200, 200))
        assertEquals(ReactiveDecision.Wait, p.observe(playing(d), 1399, 200))
        assertEquals(d.key, (p.observe(playing(d), 1400, 200) as ReactiveDecision.Correct).rejected)
    }

    @Test fun expectedTrackArrivingDuringSettlingCancelsCorrection() {
        val p = armed()
        p.observe(playing(c), 1000, 200)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(b), 1100, 200))
        assertFalse(p.waiting)
    }

    @Test fun bufferingAndUnknownMetadataCannotTriggerCorrection() {
        val p = armed()
        p.observe(playing(c), 1000, 200)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(c).copy(state = 6), 1200, 200))
        assertFalse(p.waiting)
        assertNotNull(p.roll)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(c).copy(track = null), 1300, 200))
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 1400, 200))
    }

    @Test fun aimpPausedThenPlayingSkipKeepsTheCapturedTarget() {
        val p = armed()
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c).copy(state = 2, position = 0), 1000, 200))
        assertEquals(b, p.roll?.choice?.track)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c).copy(position = 47), 1070, 200))
        val correction = p.observe(playing(c), 1270, 200) as ReactiveDecision.Correct
        assertEquals(b, correction.roll.choice.track)
        assertEquals(7L, correction.roll.sequence)
    }

    @Test fun shortPauseWithOldOrUnknownMetadataAlsoKeepsTarget() {
        for (track in listOf(a, null)) {
            val p = armed()
            p.observe(playing(a).copy(state = 2, track = track), 1000, 200)
            assertNotNull(p.roll)
            assertEquals(b, correct(p, at = 1070).roll.choice.track)
        }
    }

    @Test fun repeatedAimpSkipsContinueCorrectingAcrossTransientPauses() {
        val p = armed()
        val targets = listOf(b, a, d, b, a, d)
        targets.forEachIndexed { index, target ->
            val now = 1000L + index * 2000L
            val skipped = Track(20L + index, "skip-$index", null, "Skipped $index", "Artist")
            p.observe(playing(skipped).copy(state = 2), now, 200)
            val correction = correct(p, skipped, now + 70)
            assertEquals(target, correction.roll.choice.track)
            assertEquals(7L + index, correction.roll.sequence)
            assertEquals(ReactiveDecision.Accept(), p.observe(playing(target), now + 350, 200))
            p.arm(target.key, Choice(targets.getOrElse(index + 1) { b }), 8L + index)
        }
    }

    @Test fun secondSelectionOverrideSurvivesAimpTransientPause() {
        val p = armed()
        correct(p)
        p.observe(playing(b), 1300, 200)
        p.arm(b.key, Choice(d), 8)
        p.observe(playing(c).copy(state = 2), 1400, 200)
        assertEquals(ReactiveDecision.Accept(manualOverride = true), p.observe(playing(c), 1470, 200))
    }

    @Test fun sustainedPauseCancelsTargetAndStopsPollingAfterOneSecond() {
        val p = armed()
        val paused = playing(a).copy(state = 2)
        assertEquals(ReactiveDecision.Wait, p.observe(paused, 1000, 200))
        assertTrue(p.waiting)
        assertEquals(ReactiveDecision.Wait, p.observe(paused, 1999, 200))
        assertEquals(ReactiveDecision.Accept(), p.observe(paused, 2000, 200))
        assertNull(p.roll)
        assertFalse(p.waiting)
        assertEquals(ReactiveDecision.Accept(), p.observe(paused, 2100, 200))
        assertFalse(p.waiting)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(c), 2200, 200))
    }

    @Test fun sustainedPauseClearsOverrideButShortPauseDoesNotExtendIt() {
        for (duration in listOf(70L, 1000L)) {
            val p = armed()
            correct(p)
            p.observe(playing(b), 1300, 200)
            p.arm(b.key, Choice(d), 8)
            p.observe(playing(b).copy(state = 2), 6000, 200)
            p.observe(playing(b).copy(state = 2), 6000 + duration, 200)
            if (duration == 1000L) {
                assertNull(p.roll)
                p.arm(b.key, Choice(d), 8)
            }
            assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 7100, 200))
        }
    }

    @Test fun pauseStopAndResetDiscardCapturedTargetsAndOverrides() {
        for (state in listOf(1, 7)) {
            val p = armed()
            p.observe(playing(a).copy(state = state), 1000, 200)
            assertNull(p.roll)
            assertEquals(ReactiveDecision.Accept(), p.observe(playing(c), 1100, 200))
        }
        val p = armed()
        correct(p)
        p.reset()
        assertFalse(p.waiting)
        assertEquals(ReactiveDecision.Accept(), p.observe(playing(c), 1300, 200))
    }

    @Test fun rerollReplacesTargetAndRestartsSettling() {
        val p = armed()
        p.observe(playing(c), 1000, 200)
        p.arm(a.key, Choice(d), 8)
        assertEquals(ReactiveDecision.Wait, p.observe(playing(c), 1200, 200))
        assertEquals(d, (p.observe(playing(c), 1400, 200) as ReactiveDecision.Correct).roll.choice.track)
    }

    @Test fun emptyPoolAndCompletedPassProducePauseDecisionWithoutInventingTarget() {
        val correction = correct(armed(Choice(null)))
        assertNull(correction.roll.choice.track)
    }
}
