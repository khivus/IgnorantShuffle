package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ListeningStatisticsTest {
    private val a=Track(1,"a",null,"Track A","Artist")
    private val b=Track(2,"b",null,"Track B","Other")
    private class Harness {
        val deltas=mutableListOf<ListeningDelta>()
        val engine=ListeningStatistics { deltas.add(it) }.apply { collecting(true) }
        var elapsed=1000L
        var wall=LocalDate.of(2026,11,10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()+100_000
        val ms get()=deltas.sumOf { it.ms }
        val plays get()=deltas.sumOf { it.plays }
        fun sample(track: Track, position: Long, state: Int=3, duration: Long=180_000, speed: Float=1f, step: Long=1000) {
            elapsed+=step;wall+=step
            engine.observe(Playback(state,track.title,track.artist,position,duration,speed,elapsed,track),elapsed,wall)
        }
    }
    @Test fun longTrackQualifiesOnceAfterThirtySecondsAndDuplicatesAddNothing() {
        val h=Harness(); h.sample(a,0)
        for(i in 1..29) h.sample(a,i*1000L)
        assertEquals(29_000,h.ms);assertEquals(0,h.plays)
        h.sample(a,30_000);repeat(10) {h.sample(a,30_000,step=0)}
        assertEquals(30_000,h.ms);assertEquals(1,h.plays)
        h.sample(a,31_000);assertEquals(1,h.plays)
    }
    @Test fun shortTrackQualifiesAtHalfDurationAndUnknownDurationUsesThirtySeconds() {
        for(duration in listOf(20_000L,-1L)) {
            val h=Harness();val seconds=if(duration>0)10 else 30
            for(i in 0..seconds) h.sample(a,i*1000L,duration=duration)
            assertEquals(1,h.plays);assertEquals(seconds*1000L,h.ms)
        }
    }
    @Test fun pausesAndResumeNeverBridgePausedTime() {
        val h=Harness();h.sample(a,0);h.sample(a,1000);h.sample(a,1000,state=2)
        h.sample(a,1000,state=2,step=60_000);h.sample(a,1000);h.sample(a,2000)
        assertEquals(2000,h.ms)
    }
    @Test fun seeksBothDirectionsDoNotAddTimeOrNewPlays() {
        val h=Harness();h.sample(a,0);h.sample(a,1000);h.sample(a,90_000);h.sample(a,91_000)
        h.sample(a,1000);h.sample(a,2000)
        assertEquals(3000,h.ms);assertEquals(0,h.plays)
    }
    @Test fun longObservationGapsAndDisconnectsAreNotExtrapolated() {
        val h=Harness();h.sample(a,0);h.sample(a,1000);h.sample(a,61_000,step=60_000)
        h.sample(a,62_000);h.engine.disconnect();h.sample(a,120_000,step=58_000);h.sample(a,121_000)
        assertEquals(3000,h.ms)
    }
    @Test fun restartsOnlyCountNewlyObservedIntervals() {
        val h=Harness();h.sample(a,0);h.sample(a,1000)
        val next=ListeningStatistics { h.deltas.add(it) }.apply { collecting(true) }
        val p=Playback(3,a.title,a.artist,100_000,180_000,1f,100_000,a)
        next.observe(p,100_000,h.wall+100_000);assertEquals(1000,h.ms)
        next.observe(p.copy(position=101_000,updatedAt=101_000),101_000,h.wall+101_000)
        assertEquals(2000,h.ms)
    }
    @Test fun rejectedFragmentsAndPreviewsCannotCreateAPlay() {
        val h=Harness();h.sample(a,0);h.sample(a,1000);h.engine.reject()
        // The controller never forwards rejected B playback or preview changes.
        h.sample(a,4000);h.sample(a,5000)
        assertEquals(2000,h.ms);assertTrue(h.deltas.all {it.session.key==a.key})
    }
    @Test fun collectionPauseDoesNotBackfillAndResumeDoesNotRequalifySameSession() {
        val h=Harness();for(i in 0..30)h.sample(a,i*1000L)
        h.engine.collecting(false);h.sample(a,90_000,step=60_000)
        h.engine.collecting(true);h.sample(a,91_000);h.sample(a,92_000)
        assertEquals(31_000,h.ms);assertEquals(1,h.plays)
    }
    @Test fun observedEarlyChangeCountsExitButDisconnectDoesNot() {
        val h=Harness();h.sample(a,0);h.sample(a,1000);h.sample(b,0)
        assertEquals(1,h.deltas.sumOf {it.exits});h.sample(b,1000);h.engine.disconnect()
        assertEquals(1,h.deltas.sumOf {it.exits})
    }
    @Test fun clearStartsFreshWithoutReusingSessionIdsOrQualification() {
        val h=Harness();h.sample(a,0);h.sample(a,1000);val id=h.deltas.last().session.id
        h.engine.reset();h.sample(a,2000);h.sample(a,3000)
        assertNotEquals(id,h.deltas.last().session.id);assertEquals(1000,h.deltas.last().session.listeningMs)
    }
    @Test fun oneIntervalSplitsAcrossMidnightWithoutLosingTime() {
        val h=Harness();h.wall=LocalDate.of(2026,11,10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()-1500
        h.sample(a,0);h.sample(a,1000)
        assertEquals(1000,h.ms);assertEquals(setOf("2026-11-09","2026-11-10"),h.deltas.map {it.day}.toSet())
        assertEquals(listOf(500L,500L),h.deltas.map {it.ms})
    }
    @Test fun pollingSamePlayingSnapshotCountsRealTimeOnceButNotBeyondTrackEnd() {
        val h=Harness();val p=Playback(3,a.title,a.artist,0,4000,1f,1000,a)
        for(i in 1..8)h.engine.observe(p,i*1000L,h.wall+i*1000L)
        assertEquals(4000,h.ms);assertEquals(1,h.plays)
    }
    @Test fun futureTimestampsInvalidSpeedBufferingAndNegativePositionsAreIgnored() {
        val h=Harness();h.sample(a,0);h.sample(a,1000,speed=Float.NaN);h.sample(a,2000,speed=0f)
        h.sample(a,3000,state=6);h.sample(a,-1);h.sample(a,5000)
        assertEquals(0,h.ms)
        h.engine.observe(Playback(3,a.title,a.artist,6000,180_000,1f,h.elapsed+10_000,a),h.elapsed,h.wall)
        assertEquals(0,h.ms)
    }
    @Test fun speedChangesDoNotInventListeningAndDoubleSpeedUsesWallTime() {
        val h=Harness();h.sample(a,0,speed=2f);h.sample(a,2000,speed=2f);h.sample(a,3000,speed=1f)
        h.sample(a,4000);assertEquals(2000,h.ms)
    }
    @Test fun dateFiltersAreInclusiveCalendarDays() {
        val now=LocalDate.of(2026,11,10)
        assertEquals("2026-11-10",StatisticsRange.TODAY.since(now))
        assertEquals("2026-11-04",StatisticsRange.WEEK.since(now))
        assertEquals("2026-10-12",StatisticsRange.MONTH.since(now))
        assertEquals("",StatisticsRange.ALL.since(now))
    }
}
