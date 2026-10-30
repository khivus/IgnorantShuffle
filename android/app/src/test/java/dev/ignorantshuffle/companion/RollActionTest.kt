package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class RollActionTest {
    private val track = Track(1, "selected", null, "Selected track", "Artist")
    private val action = RollAction("process-one", 7, track.key)

    @Test fun actionMatchesTheDisplayedWeightedRoll() {
        assertTrue(action.matches("process-one", 7, Choice(track)))
    }

    @Test fun oldActionCannotPlayAfterRerollOrProcessRestart() {
        assertFalse(action.matches("process-one", 8, Choice(track)))
        assertFalse(action.matches("process-two", 7, Choice(track)))
    }

    @Test fun removedOrReplacedTrackCannotBePlayed() {
        assertFalse(action.matches("process-one", 7, Choice(null)))
        assertFalse(action.matches("process-one", 7, Choice(track.copy(mediaId = "other"))))
    }

    @Test fun queueRenumberingKeepsTheCommittedTrack() {
        assertTrue(action.matches("process-one", 7, Choice(track.copy(queueId = 99))))
    }
}
