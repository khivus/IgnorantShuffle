package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class PreviousRollActionTest {
    private val track = Track(1, "previous", null, "Previous", "Artist")
    private val previous = Choice(track, historyIndex = 2)
    private val action = PreviousRollAction("process-one", 7, "current", track.key, 2)

    @Test fun previousIsBoundToSourceAndExactHistoryOccurrence() {
        assertTrue(action.matches("process-one", 7, "current", previous))
        assertFalse(action.matches("process-one", 7, "new-source", previous))
        assertFalse(action.matches("process-one", 7, null, previous))
        assertFalse(action.matches("process-one", 7, "current", previous.copy(historyIndex = 0)))
    }

    @Test fun oldPreviousIsRejectedAfterRerollRestartOrHistoryRemoval() {
        assertFalse(action.matches("process-two", 7, "current", previous))
        assertFalse(action.matches("process-one", 8, "current", previous))
        assertFalse(action.matches("process-one", 7, "current", null))
        assertFalse(action.matches("process-one", 7, "current", previous.copy(track = track.copy(mediaId = "other"))))
    }

    @Test fun queueRenumberingDoesNotChangeTheHistoryTarget() {
        assertTrue(action.matches("process-one", 7, "current", previous.copy(track = track.copy(queueId = 90))))
    }
}
