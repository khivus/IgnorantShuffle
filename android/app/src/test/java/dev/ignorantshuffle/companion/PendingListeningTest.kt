package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class PendingListeningTest {
    @Test fun batchesPreserveMidnightQualificationEarlyExitAndLatestSnapshot() {
        val batch=PendingListening()
        val s=ListeningSession("a","song","Song","Artist",1000,2000,1000)
        batch.add(ListeningDelta(s,"2026-11-13",400))
        batch.add(ListeningDelta(s,"2026-11-14",600))
        val latest=s.copy(updated=3000,listeningMs=2000,qualified=true)
        batch.add(ListeningDelta(latest,"2026-11-14",1000,plays=1))
        batch.add(ListeningDelta(s.copy(id="b",earlyExit=true),"2026-11-14",exits=1))
        val rows=batch.values()
        assertEquals(3,rows.size)
        assertEquals(2000L,rows.sumOf {it.ms})
        assertEquals(1,rows.sumOf {it.plays});assertEquals(1,rows.sumOf {it.exits})
        assertEquals(latest,rows[1].session)
        batch.clear();assertTrue(batch.values().isEmpty())
    }
}
