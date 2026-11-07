package dev.ignorantshuffle.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class AimpPlaybackModeTest {
    @Test fun onlyReportedShuffleModesEnableAutomaticSwitching() {
        assertEquals(AimpShuffleMode.UNKNOWN, AimpShuffleMode.fromSession(-1))
        assertEquals(AimpShuffleMode.OFF, AimpShuffleMode.fromSession(0))
        assertEquals(AimpShuffleMode.ON, AimpShuffleMode.fromSession(1))
        assertEquals(AimpShuffleMode.ON, AimpShuffleMode.fromSession(2))
        assertEquals(AimpShuffleMode.UNKNOWN, AimpShuffleMode.fromSession(99))
    }
}
