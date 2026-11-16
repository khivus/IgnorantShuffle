package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class WeightPresentationTest {
    @Test fun pointEditsRoundTripAndRespectPerTrackLimit() {
        val score=WeightPresentation.scoreFromPoints(7.0,0.4,0.15)
        assertEquals(0.4,7*score,1e-9)
        assertEquals("7 + 0.4 = 7.4",WeightPresentation.summary(7.0,score))
        assertEquals("7 − 0.4 = 6.6",WeightPresentation.summary(7.0,-score))
        assertEquals(0.0,WeightPresentation.scoreFromPoints(0.0,0.0,0.15),0.0)
        assertThrows(IllegalArgumentException::class.java) { WeightPresentation.scoreFromPoints(0.0,0.4,0.15) }
        assertThrows(IllegalArgumentException::class.java) { WeightPresentation.scoreFromPoints(7.0,1.1,0.15) }
        assertThrows(IllegalArgumentException::class.java) { WeightPresentation.scoreFromPoints(7.0,Double.NaN,0.15) }
    }
}
