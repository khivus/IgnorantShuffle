package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class PlaybackAuditTest {
    private val source = Track(1, "source", null, "Source", "Artist")
    private val target = Track(2, "target", null, "Expected", "Artist")
    private val wrong = Track(3, "wrong", null, "Different", "Artist")
    private val before = Playback(state = 3, track = source, title = source.title, position = 9000)
    private fun playing(track: Track) = before.copy(track = track, title = track.title, position = 0)
    private fun audit() = PlaybackAudit().apply { arm(before, target, 4, 1000) }

    @Test fun confirmsObservedTargetAndReportsOnlyOnce() {
        val audit = audit()
        assertTrue(audit.observe(playing(target), 1100)!!.matched)
        assertFalse(audit.active)
        assertNull(audit.observe(playing(wrong), 9000))
    }
    @Test fun waitsThroughOldMetadataAndBuffering() {
        val audit = audit()
        assertNull(audit.observe(before, 1100))
        assertNull(audit.observe(playing(wrong).copy(state = 6), 1200))
        assertNull(audit.observe(playing(wrong), 1300))
        assertTrue(audit.observe(playing(target), 1400)!!.matched)
    }
    @Test fun stableWrongTrackProducesExpectedAndActualMessage() {
        val audit = audit()
        assertNull(audit.observe(playing(wrong), 1200))
        val result = audit.observe(playing(wrong), 1950)!!
        assertFalse(result.matched)
        assertTrue(result.message.contains("Expected"))
        assertTrue(result.message.contains("Different"))
        assertTrue(result.message.contains("roll #4"))
    }
    @Test fun neverStartingTimesOutButPauseAloneIsNotAWrongTrack() {
        val audit = audit()
        assertNull(audit.observe(before.copy(state = 2), 8999))
        val result = audit.observe(before.copy(state = 2), 9000)!!
        assertFalse(result.matched)
        assertTrue(result.message.contains("8 seconds"))
    }
    @Test fun cancellationAndRearmingDoNotLeakEarlierExpectations() {
        val audit = audit()
        audit.cancel()
        assertNull(audit.observe(playing(wrong), 10000))
        audit.arm(before, wrong, 5, 10001)
        assertTrue(audit.observe(playing(wrong), 10002)!!.matched)
    }
    @Test fun sameTrackSelectionRequiresRestartEvidence() {
        val audit = PlaybackAudit().apply { arm(before, source, 6, 1000) }
        assertNull(audit.observe(before, 1100))
        assertTrue(audit.observe(before.copy(position = 0), 1200)!!.matched)
    }
    @Test fun wrongTrackGraceResetsWhenMetadataReturnsToSource() {
        val audit = audit()
        assertNull(audit.observe(playing(wrong), 1100))
        assertNull(audit.observe(before, 1500))
        assertNull(audit.observe(playing(wrong), 1800))
        assertTrue(audit.observe(playing(target), 2000)!!.matched)
    }
}
