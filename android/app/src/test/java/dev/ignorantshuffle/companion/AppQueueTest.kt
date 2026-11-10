package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class AppQueueTest {
    private val a=Track(1,"a",null,"A","Artist")
    private val b=Track(2,"b",null,"B","Artist")
    private fun model()=ShuffleModel(Planner { tracks,weights,_,_,_,_ -> Choice(tracks.firstOrNull { weights[tracks.indexOf(it)]>0 }) }).apply { catalog(listOf(a,b)) }
    @Test fun queuePrecedesWeightsAndFinitePassButPreviewDoesNotConsumeIt() {
        val m=model();m.configure(Settings(finite=true),mapOf(b.key to 0.0));val entry=QueueEntry.from(b)
        m.setQueue(listOf(entry));repeat(5) {assertEquals(entry.id,m.next().queueEntryId)}
        assertEquals(1,m.queue.size);assertTrue(m.played.isEmpty())
        val choice=m.next();m.requested(choice);m.confirmPlaying(b)
        assertTrue(m.queue.isEmpty());assertEquals(setOf(b.key),m.played)
    }
    @Test fun failedOrWrongPlaybackDoesNotConsumeQueue() {
        val m=model();m.setQueue(listOf(QueueEntry.from(b)));m.requested(m.next());m.cancelRequest();m.confirmPlaying(a)
        assertEquals(1,m.queue.size);assertEquals(b,m.next().track)
    }
    @Test fun intermediateWrongPlaybackDoesNotLosePendingQueueConfirmation() {
        val m=model();m.setQueue(listOf(QueueEntry.from(b)));m.requested(m.next())
        m.confirmPlaying(a);assertEquals(1,m.queue.size)
        m.confirmPlaying(b);assertTrue(m.queue.isEmpty())
    }
    @Test fun newListeningTotalsKeepPublishedRollStable() {
        val m=model();m.balance(true,emptyMap());val choice=m.next();val sequence=m.choiceSequence
        m.balance(true,mapOf(a.key to 20L));assertEquals(choice,m.next());assertEquals(sequence,m.choiceSequence)
        assertTrue(m.selectionWeight(a)<m.selectionWeight(b))
    }
    @Test fun duplicateEntriesConsumeOneAtATimeOnlyAfterConfirmedRestart() {
        val m=model();val entries=listOf(QueueEntry.from(a),QueueEntry.from(a));m.setQueue(entries)
        m.requested(m.next());m.confirmPlaying(a);assertEquals(1,m.queue.size)
        m.requested(m.next());m.confirmPlaying(a);assertEquals(1,m.queue.size)
        m.confirmPlaying(a,restarted=true);assertTrue(m.queue.isEmpty())
    }
    @Test fun unavailableEntriesAreRetainedAndQueueIdsAreResolvedFromFreshSession() {
        val m=model();val entries=listOf(QueueEntry.from(a),QueueEntry.from(b));m.setQueue(entries)
        m.catalog(listOf(b.copy(queueId=999)));assertEquals(999,m.next().track!!.queueId)
        m.requested(m.next());m.confirmPlaying(b);assertEquals(listOf(entries.first()),m.queue)
        m.disconnect();assertEquals(1,m.queue.size);m.catalog(listOf(a.copy(queueId=55)))
        assertEquals(55,m.next().track!!.queueId)
    }
    @Test fun queueStoragePreservesUnicodeDuplicatesAndOrderWithoutSessionIds() {
        val entries=listOf(QueueEntry.from(a.copy(title="Музыка 🎵")),QueueEntry.from(a))
        val text=QueueEntry.encode(entries);assertEquals(entries,QueueEntry.decode(text));assertFalse(text.contains("queueId"))
    }
    @Test fun balancingBoostsLessPlayedAndReducesOftenPlayedWithoutChangingSavedWeightsOrExclusions() {
        val m=model();m.balance(true,mapOf(a.key to 20L,b.key to 0L))
        assertTrue(m.selectionWeight(a)<5);assertTrue(m.selectionWeight(b)>5)
        assertEquals(5.0,m.weight(a),0.0)
        m.configure(Settings(),mapOf(b.key to 0.0));assertEquals(0.0,m.selectionWeight(b),0.0)
        m.balance(false,emptyMap());assertEquals(5.0,m.selectionWeight(a),0.0)
    }
    @Test fun balanceBoundsAndEmptyStatisticsAreNeutral() {
        assertEquals(1.0,ListeningBalance.factor(a.key,emptyMap(),listOf(a,b)),0.0)
        assertEquals(4.0,ListeningBalance.factor(b.key,mapOf(a.key to Long.MAX_VALUE),listOf(a,b)),0.0)
        assertEquals(0.25,ListeningBalance.factor(a.key,mapOf(a.key to Long.MAX_VALUE),listOf(a,b, b.copy(mediaId="c"), b.copy(mediaId="d"), b.copy(mediaId="e"))),0.0)
    }
    @Test fun balancePreferenceDefaultsOffAndRoundTrips() {
        assertFalse(PreferenceJson.decode("{}").balanceListening)
        val p=AppPreferences(balanceListening=true);assertEquals(p,PreferenceJson.decode(PreferenceJson.encode(p)))
    }
    @Test fun statisticsExportsEscapeCsvAndKeepShuffleCountersAndExactTotals() {
        val row=JSONObject().put("session","id").put("track",a.key).put("title","A, \"quoted\"\ntrack").put("artist","Артист")
            .put("started",1L).put("updated",2L).put("day","2026-11-10").put("listening_ms",1234L).put("qualified_plays",1).put("early_exits",0)
        val rows=JSONArray().put(row);val counters=JSONArray().put(JSONObject().put("day","2026-11-10").put("kind","reroll").put("count",2))
        val json=JSONObject(StatisticsExport.encode("json",rows,counters))
        assertEquals(1234L,json.getJSONArray("sessions_by_day").getJSONObject(0).getLong("listening_ms"))
        assertEquals(2,json.getJSONArray("shuffle").getJSONObject(0).getInt("count"))
        val csv=StatisticsExport.encode("csv",rows,counters)
        assertTrue(csv.contains("\"A, \"\"quoted\"\"\ntrack\""));assertTrue(csv.contains("Артист"));assertTrue(csv.contains("\"reroll\",\"2\""))
    }
}
