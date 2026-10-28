package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class ShuffleModelTest {
    private val a = Track(0, "a", null, "A", "Artist")
    private val b = Track(1, "b", null, "B", "Artist")
    private class FakePlanner : Planner {
        var calls = 0
        var avoid = ""
        var recent: List<String> = emptyList()
        var played: List<String> = emptyList()
        var numbers = doubleArrayOf()
        override fun choose(tracks: List<Track>, weights: DoubleArray, recent: List<String>, played: List<String>,
            settings: Settings, avoid: String): Choice {
            calls++; this.avoid = avoid; this.recent = recent; this.played = played; numbers = weights
            return Choice(tracks.firstOrNull())
        }
    }
    private fun model(p: FakePlanner = FakePlanner()) = ShuffleModel(p).also { it.catalog(listOf(a, b)) }

    @Test fun previewIsStableAndDoesNotConsumeProgress() {
        val planner = FakePlanner(); val model = model(planner)
        repeat(10) { model.next() }
        assertEquals(1, planner.calls); assertTrue(model.recent.isEmpty()); assertTrue(model.played.isEmpty())
    }
    @Test fun rerollPassesPreviousChoiceToSharedRules() {
        val p = FakePlanner(); val model = model(p)
        model.next(); model.next(true)
        assertEquals(a.key, p.avoid); assertEquals(2, p.calls); assertTrue(model.recent.isEmpty())
    }
    @Test fun onlyConfirmedChangesAdvanceHistory() {
        val model = model()
        model.requested(Choice(a)); assertTrue(model.recent.isEmpty())
        model.confirmPlaying(a); model.confirmPlaying(a); model.confirmPlaying(b)
        assertEquals(listOf(a.key, b.key), model.recent)
    }
    @Test fun previousThenNextUsesListeningHistory() {
        val model = model(); model.confirmPlaying(a); model.confirmPlaying(b)
        val previous = model.previous()!!; assertEquals(a.key, previous.track!!.key)
        model.requested(previous); model.confirmPlaying(a)
        assertEquals(b.key, model.next().track!!.key); assertNotNull(model.next().historyIndex)
    }
    @Test fun excludedHistoryEntryCannotBeSelectedBySmartPrevious() {
        val model = model(); model.confirmPlaying(a); model.confirmPlaying(b)
        model.configure(Settings(), mapOf(a.key to 0.0)); assertNull(model.previous())
    }
    @Test fun enablingFiniteCountsCurrentOnlyAfterItWasObserved() {
        val model = model(); model.confirmPlaying(a)
        model.configure(Settings(finite = true), emptyMap())
        assertEquals(setOf(a.key), model.played)
        model.next(); assertEquals(setOf(a.key), model.played)
    }
    @Test fun explicitWeightsAndFallbackArePassedWithoutClamping() {
        val p = FakePlanner(); val model = model(p)
        model.configure(Settings(defaultWeight = 2.5), mapOf(a.key to 25.0)); model.next()
        assertArrayEquals(doubleArrayOf(25.0, 2.5), p.numbers, 0.0)
    }
    @Test fun renumberedQueueKeepsPreviewButUsesFreshQueueId() {
        val p = FakePlanner(); val model = model(p); model.next()
        model.catalog(listOf(a.copy(queueId = 99), b))
        assertEquals(99L, model.next().track!!.queueId); assertEquals(1, p.calls)
    }
    @Test fun reusedMediaIdWithDifferentMetadataReceivesNoOldWeight() {
        val model = model(); model.configure(Settings(), mapOf(a.key to 0.0))
        val replacement = a.copy(title = "Different song")
        assertNotEquals(a.key, replacement.key); assertEquals(5.0, model.weight(replacement), 0.0)
    }
    @Test fun disconnectClearsSessionProgress() {
        val model = model(); model.confirmPlaying(a); model.disconnect()
        assertNull(model.currentKey); assertTrue(model.tracks.isEmpty()); assertTrue(model.recent.isEmpty()); assertNull(model.previous())
    }
    @Test fun transfersPreserveZeroFractionAndLargeWeight() {
        val values = mapOf(a.key to 0.0, b.key to 25.5)
        assertEquals(values, WeightTransfer.parse(WeightTransfer.write(values)))
    }
    @Test fun invalidTransferIsRejectedAsAWhole() {
        listOf("-1", "NaN", "Infinity", "1e999").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { WeightTransfer.parse("1\t${a.key}\n$value\t${b.key}") }
        }
        assertThrows(IllegalArgumentException::class.java) { WeightTransfer.parse("5\tD:\\music\\a.mp3") }
    }
    @Test fun settingsRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { Settings(Double.NaN).validate() }
        assertThrows(IllegalArgumentException::class.java) { Settings(cooldown = 10001).validate() }
    }
}
