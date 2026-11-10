package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SmartWeightsTest {
    private val a=Track(1,"a",null,"A","Artist")
    private val b=Track(2,"b",null,"B","Artist")
    private inner class Listening {
        val signals=mutableListOf<SmartFeedback>()
        val engine=ListeningStatistics(feedback={ signals.add(it) },emit={}).apply { collecting(true) }
        var elapsed=1000L
        fun sample(track: Track,position: Long,duration: Long=100_000,state: Int=3,restart: Boolean=false,
            replay: Boolean=false,automatic: Boolean=false,step: Long=1000) {
            elapsed+=step
            engine.observe(Playback(state,track.title,track.artist,position,duration,1f,elapsed,track),elapsed,elapsed,
                restart,replay,automatic)
        }
        fun volume(level: Int,maximum: Int=15) = engine.observeVolume(Playback(3,a.title,a.artist,0,100_000,1f,elapsed,a),ListeningVolume(1,2,maximum,level),elapsed)
        fun count(kind: SmartSignal)=signals.count {it.kind==kind}
    }
    @Test fun stepsAreSmallBoundedAndIndividuallyOptional() {
        val o=SmartWeightOptions(enabled=true)
        assertEquals(-0.02,o.step(SmartSignal.EARLY_SKIP),0.0)
        assertEquals(0.005,o.step(SmartSignal.COMPLETED),0.0)
        assertEquals(0.01,o.step(SmartSignal.REPLAYED),0.0)
        assertEquals(0.0025,o.step(SmartSignal.VOLUME_UP),0.0)
        assertEquals(0.0,o.copy(enabled=false).adjustment(0.0,SmartSignal.COMPLETED,0.0),0.0)
        assertEquals(0.0,o.copy(skips=false).step(SmartSignal.EARLY_SKIP),0.0)
        assertEquals(0.0,o.copy(completions=false).step(SmartSignal.COMPLETED),0.0)
        assertEquals(0.0,o.copy(replays=false).step(SmartSignal.REPLAYED),0.0)
        assertEquals(0.0,o.copy(volume=false).step(SmartSignal.VOLUME_UP),0.0)
        assertEquals(0.005,o.adjustment(0.0,SmartSignal.REPLAYED,0.01),0.000001)
        assertEquals(0.0,o.adjustment(0.0,SmartSignal.COMPLETED,0.015),0.000001)
    }
    @Test fun manySignalsCannotRunAwayAndNegativeFeedbackCanUndoSaturation() {
        val o=SmartWeightOptions(enabled=true);var score=0.0
        repeat(10000) { score+=o.adjustment(score,SmartSignal.COMPLETED,0.0) }
        assertEquals(0.15,score,0.000001)
        score+=o.adjustment(score,SmartSignal.EARLY_SKIP,0.0);assertEquals(0.13,score,0.000001)
        repeat(10000) { score+=o.adjustment(score,SmartSignal.EARLY_SKIP,0.0) }
        assertEquals(-0.15,score,0.000001)
        assertEquals(1.05,o.copy(limit=0.05).factor(1000.0),0.000001)
        assertEquals(0.95,o.copy(limit=0.05).factor(-1000.0),0.000001)
    }
    @Test fun configurationRoundTripsAndLegacyInstallsStayOptedOut() {
        val legacy=PreferenceJson.decode("{}")
        assertFalse(legacy.smartWeights.enabled);assertEquals(1.0,legacy.balanceStrength,0.0)
        val p=AppPreferences(balanceListening=true,balanceStrength=0.5,smartWeights=SmartWeightOptions(true,0.5,0.05,false,true,false,true))
        assertEquals(p,PreferenceJson.decode(PreferenceJson.encode(p)))
        for(value in listOf(-1.0,0.0,Double.NaN,Double.POSITIVE_INFINITY,2.1)) {
            assertThrows(IllegalArgumentException::class.java) { PreferenceJson.encode(p.copy(smartWeights=p.smartWeights.copy(strength=value))) }
        }
        assertThrows(IllegalArgumentException::class.java) { PreferenceJson.decode("""{"smartWeights":{"limit":10}}""") }
        assertThrows(IllegalArgumentException::class.java) { PreferenceJson.decode("""{"balanceStrength":2}""") }
    }
    @Test fun balanceAndSmartCombineWithoutChangingBaseEligibilityOrPublishedChoices() {
        val m=ShuffleModel(Planner { tracks,_,_,_,_,_ -> Choice(tracks.first()) })
        m.catalog(listOf(a,b));m.balance(true,mapOf(a.key to 10L),0.5)
        m.smartWeights(SmartWeightOptions(true),mapOf(a.key to -0.1,b.key to 0.1))
        assertTrue(m.selectionWeight(a)<5.0);assertTrue(m.selectionWeight(b)>5.0)
        assertEquals(5.0,m.weight(a),0.0)
        val choice=m.next();val sequence=m.choiceSequence
        m.smartWeights(SmartWeightOptions(true),mapOf(a.key to 0.1))
        assertEquals(choice,m.next());assertEquals(sequence,m.choiceSequence)
        m.configure(Settings(),mapOf(a.key to 0.0,b.key to 0.5))
        assertEquals(0.0,m.selectionWeight(a),0.0);assertEquals(0.0,m.selectionWeight(b),0.0)
    }
    @Test fun balanceStrengthSoftensBothBoostsAndPenalties() {
        val m=ShuffleModel(Planner { _,_,_,_,_,_ -> Choice(null) });m.catalog(listOf(a,b))
        m.balance(true,mapOf(a.key to 10L),1.0);val full=m.selectionWeight(a);val fullBoost=m.selectionWeight(b)
        m.balance(true,mapOf(a.key to 10L),0.25)
        assertTrue(m.selectionWeight(a)>full);assertTrue(m.selectionWeight(a)<5.0)
        assertTrue(m.selectionWeight(b)<fullBoost);assertTrue(m.selectionWeight(b)>5.0)
    }
    @Test fun earlyAcceptedSkipPenalizesOnceButPauseStopNoiseAndDisconnectDoNot() {
        val l=Listening();l.sample(a,0);l.sample(a,1000);l.sample(a,2000);l.sample(b,0)
        repeat(4) {l.sample(b,0,step=0)}
        assertEquals(1,l.count(SmartSignal.EARLY_SKIP));assertEquals(a.key,l.signals.first().key)
        val stop=Listening();stop.sample(a,0);stop.sample(a,1000);stop.sample(a,2000);stop.sample(a,2000,state=1)
        assertTrue(stop.signals.isEmpty())
        val pause=Listening();pause.sample(a,0);pause.sample(a,1000);pause.sample(a,2000);pause.sample(a,2000,state=2)
        pause.sample(b,0,step=10_000);assertTrue(pause.signals.isEmpty())
        val short=Listening();short.sample(a,0);short.sample(a,1000);short.sample(b,0);assertTrue(short.signals.isEmpty())
    }
    @Test fun completedPlayNeedsActualListeningAndEndingNotASeekOrLongGap() {
        val l=Listening();for(i in 0..99)l.sample(a,i*1000L)
        assertEquals(1,l.count(SmartSignal.COMPLETED))
        l.sample(a,100_000);assertEquals(1,l.count(SmartSignal.COMPLETED))
        val seek=Listening();seek.sample(a,0);seek.sample(a,1000);seek.sample(a,98_000);seek.sample(a,99_000);seek.sample(b,0)
        assertEquals(0,seek.count(SmartSignal.COMPLETED));assertEquals(0,seek.count(SmartSignal.EARLY_SKIP))
        val gap=Listening();gap.sample(a,0);gap.sample(a,99_000,step=99_000);assertTrue(gap.signals.isEmpty())
    }
    @Test fun unqualifiedShortSkipsAfterASeekAreAmbiguousAndDoNotLearn() {
        val l=Listening();for(i in 0..3)l.sample(a,i*1000L)
        l.sample(a,80_000);l.sample(b,0)
        assertEquals(0,l.count(SmartSignal.EARLY_SKIP))
    }
    @Test fun seekingAfterEnoughAccumulatedListeningStillDoesNotEarnAFullListen() {
        val l=Listening();for(i in 0..95)l.sample(a,i*1000L)
        l.sample(a,98_000);l.sample(a,99_000)
        assertEquals(0,l.count(SmartSignal.COMPLETED))
        val duplicate=Listening();for(i in 0..99) {duplicate.sample(a,i*1000L);duplicate.sample(a,i*1000L,step=0)}
        assertEquals(1,duplicate.count(SmartSignal.COMPLETED))
    }
    @Test fun naturalRepeatAndConfirmedAppReplayOnlyGainAfterQualifying() {
        val l=Listening();for(i in 0..100)l.sample(a,i*1000L)
        l.sample(a,0);for(i in 1..29)l.sample(a,i*1000L)
        assertEquals(0,l.count(SmartSignal.REPLAYED))
        l.sample(a,30_000);assertEquals(1,l.count(SmartSignal.REPLAYED))
        val app=Listening();app.sample(a,0);app.sample(a,1000);app.sample(a,0,restart=true,replay=true)
        for(i in 1..30)app.sample(a,i*1000L)
        assertEquals(1,app.count(SmartSignal.REPLAYED))
    }
    @Test fun automaticSameTrackHandoffAndRepeatedCallbacksAreNotReplayIntent() {
        val l=Listening();for(i in 0..100)l.sample(a,i*1000L)
        l.sample(a,0,restart=true,automatic=true);for(i in 1..30)l.sample(a,i*1000L)
        assertEquals(0,l.count(SmartSignal.REPLAYED))
        repeat(30) {l.sample(a,30_000,step=0)}
        assertEquals(1,l.count(SmartSignal.COMPLETED))
    }
    @Test fun rejectedFragmentsAndRestartNeverReceiveTheirOwnFeedback() {
        val l=Listening();l.sample(a,0);l.sample(a,1000);l.engine.reject();l.engine.disconnect()
        l.sample(b,80_000,step=80_000);l.sample(b,81_000);assertTrue(l.signals.isEmpty())
    }
    @Test fun volumeIsOneWeakQualifiedSignalAndDownUpSpamDoesNotStack() {
        val l=Listening();for(i in 0..30) {l.sample(a,i*1000L);l.volume(5)}
        l.volume(6);l.volume(6);l.volume(5);l.volume(6)
        assertEquals(1,l.count(SmartSignal.VOLUME_UP))
    }
    @Test fun volumeBeforeQualificationWaitsAndRouteChangesAndPausesDiscardIt() {
        val l=Listening();for(i in 0..10) {l.sample(a,i*1000L);l.volume(5)}
        l.volume(6);assertEquals(0,l.count(SmartSignal.VOLUME_UP))
        for(i in 11..30)l.sample(a,i*1000L)
        assertEquals(1,l.count(SmartSignal.VOLUME_UP))
        val route=Listening();for(i in 0..10) {route.sample(a,i*1000L);route.volume(5)}
        route.volume(6);route.engine.volumeRouteChanged(route.elapsed)
        for(i in 11..30) {route.sample(a,i*1000L);route.volume(10,30)}
        assertEquals(0,route.count(SmartSignal.VOLUME_UP))
        route.engine.collecting(false);route.volume(15,30);route.engine.collecting(true)
        route.sample(a,31_000);route.volume(20,30);assertEquals(0,route.count(SmartSignal.VOLUME_UP))
    }
    @Test fun volumeBaselineDuplicateCallbacksAndRoutingNeverLookLikeUserIncreases() {
        val e=VolumeEvidence();val v=ListeningVolume(1,2,15,5,"speaker")
        assertFalse(e.observe(v,1000));assertFalse(e.observe(v.copy(value=6),2000))
        assertTrue(e.observe(v.copy(value=7),7000));assertFalse(e.observe(v.copy(value=7),7000))
        assertFalse(e.observe(v.copy(value=10,route="bluetooth"),8000))
        assertFalse(e.observe(v.copy(value=11,route="bluetooth"),9000))
        e.reset(10_000);assertFalse(e.observe(v.copy(value=15),10_000))
        assertFalse(e.observe(v.copy(control=0),20_000));assertFalse(e.observe(null,21_000))
    }
}
