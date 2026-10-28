package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsAndHandoffTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun journalSurvivesRestartAndRetainsUnicode() {
        val folder = temporary.newFolder()
        DiagnosticJournal(folder).append("ROLL 1: Моря и океаны")
        assertTrue(DiagnosticJournal(folder).tail().single().contains("Моря и океаны"))
    }
    @Test fun journalRotatesAndRemainsBounded() {
        val folder = temporary.newFolder()
        val journal = DiagnosticJournal(folder, 8)
        journal.append("11111111"); journal.append("22222222"); journal.append("33333333")
        assertFalse(journal.contents().contains("11111111"))
        assertTrue(journal.contents().contains("22222222")); assertTrue(journal.contents().contains("33333333"))
        assertEquals(2, folder.listFiles()!!.size)
    }
    @Test fun loggingFailureIsVisibleAndDoesNotThrow() {
        val file = temporary.newFile()
        val journal = DiagnosticJournal(file)
        journal.append("test"); assertNotNull(journal.failure)
    }
    private class Handle : LeaseHandle {
        var acquired = 0; var released = 0
        override fun acquire(timeout: Long) { assertEquals(600_000L, timeout); acquired++ }
        override fun release() { released++ }
    }
    @Test fun cpuLeaseReleasesOnPauseOrDisableAndCancelsRenewal() {
        val handle = Handle(); var task: Runnable? = null
        val lease = CpuLease(handle, { r, _ -> task = r }, { if (task === it) task = null }, {})
        lease.update(true); lease.update(true); assertEquals(1, handle.acquired)
        val old = task!!
        lease.update(false); assertEquals(1, handle.released); assertNull(task)
        old.run(); assertEquals(1, handle.acquired)
    }
    @Test fun cpuLeaseRenewsWithoutSessionPolling() {
        val handle = Handle(); var task: Runnable? = null
        val lease = CpuLease(handle, { r, delay -> assertEquals(300_000L, delay); task = r }, {}, {})
        lease.update(true); task!!.run(); assertEquals(2, handle.acquired)
        lease.update(false); assertEquals(1, handle.released)
    }
    @Test fun cpuLeaseFailureDisablesInsteadOfCrashing() {
        var failures = 0
        val lease = CpuLease(object : LeaseHandle {
            override fun acquire(timeout: Long) { throw SecurityException("denied") }
            override fun release() {}
        }, { _, _ -> fail() }, {}, {}, { failures++ })
        lease.update(true); assertEquals(1, failures)
    }
    @Test fun handoffUsesPublishedChoiceAndRejectsAnotherRoll() {
        val track = Track(4, "b", null, "Published", "Artist")
        val plan = ScheduledRoll("source", Choice(track), 7, 10_700, 10_700)
        val sample = TimingSnapshot("source", 3, 20_000, 30_000, 1f, 1000)
        assertTrue(HandoffPolicy.valid(plan, sample, 7, 10_700))
        assertFalse(HandoffPolicy.valid(plan, sample, 8, 10_700))
        assertEquals("b", plan.choice.track!!.mediaId)
    }
    @Test fun lateOrDifferentSourceCannotDispatchCapturedChoice() {
        val plan = ScheduledRoll("source", Choice(null), 1, 10_700, 10_700)
        val sample = TimingSnapshot("other", 3, 20_000, 30_000, 1f, 1000)
        assertFalse(HandoffPolicy.valid(plan, sample, 1, 10_700))
        assertFalse(HandoffPolicy.valid(plan, sample.copy(key = "source"), 1, 15_000))
    }
    @Test fun preferencesDefaultToAutomaticLogsAndAimpOrange() {
        val prefs = AppPreferences()
        assertTrue(prefs.logging); assertEquals(Accent.ORANGE, prefs.accent)
        assertEquals(ThemeMode.SYSTEM, prefs.theme); assertFalse(prefs.notifyRoll)
    }
}
