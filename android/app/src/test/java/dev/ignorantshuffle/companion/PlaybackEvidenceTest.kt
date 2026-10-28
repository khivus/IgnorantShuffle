package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class PlaybackEvidenceTest {
    private val a = Track(1, "a", null, "A", "Artist")
    private val b = Track(2, "b", null, "B", "Artist")
    private val before = Playback(state = 3, position = 5000, track = a)
    @Test fun dispatchWithoutStateChangeIsNotConfirmation() {
        assertFalse(PlaybackEvidence.confirmed("Play B", b.key, before, before))
    }
    @Test fun renumberedTrackConfirmsByCapturedIdentity() {
        assertTrue(PlaybackEvidence.confirmed("Play B", b.key, before, before.copy(track = b.copy(queueId = 99))))
    }
    @Test fun reusedQueueIdWithWrongMetadataDoesNotConfirm() {
        assertFalse(PlaybackEvidence.confirmed("Play B", b.key, before, before.copy(track = a.copy(queueId = 2))))
    }
    @Test fun pausedOrMissingIdentityDoesNotConfirmSelection() {
        assertFalse(PlaybackEvidence.confirmed("Play B", b.key, before, before.copy(track = b, state = 2)))
        assertFalse(PlaybackEvidence.confirmed("Play B", b.key, before, before.copy(track = null)))
    }
    @Test fun alreadyCurrentNeedsARealRestartOrResume() {
        assertFalse(PlaybackEvidence.confirmed("Play A", a.key, before, before))
        assertTrue(PlaybackEvidence.confirmed("Play A", a.key, before, before.copy(position = 10)))
        assertTrue(PlaybackEvidence.confirmed("Play A", a.key, before.copy(state = 2), before))
    }
    @Test fun basicCommandsNeedObservedTransitions() {
        assertFalse(PlaybackEvidence.confirmed("Play", null, before, before))
        assertTrue(PlaybackEvidence.confirmed("Pause", null, before, before.copy(state = 2)))
        assertFalse(PlaybackEvidence.confirmed("Pause", null, before.copy(state = 2), before.copy(state = 2)))
    }
}
