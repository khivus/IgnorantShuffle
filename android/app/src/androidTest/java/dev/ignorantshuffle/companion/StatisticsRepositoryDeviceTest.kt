@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
package dev.ignorantshuffle.companion

import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import java.io.File
import org.json.JSONObject

/** Real SQLite persistence, filtering, clearing and restart checks in isolated disposable app cache. */
class StatisticsRepositoryDeviceTest : InstrumentationTestCase() {
    private lateinit var directory: File
    private lateinit var context: ContextWrapper
    private var repository: StatisticsRepository? = null
    private var counts: Map<String,Long> = emptyMap()
    override fun setUp() {
        super.setUp()
        directory=File(instrumentation.targetContext.cacheDir,"statistics-test-${System.nanoTime()}").apply { mkdirs() }
        context=object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir()=directory }
        open()
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun open() {
        main { repository=StatisticsRepository(context) { counts=it } }
        await { repository!!.ready }
    }
    private fun await(condition: () -> Boolean) {
        val deadline=System.currentTimeMillis()+10_000
        while(System.currentTimeMillis()<deadline) {
            var done=false;var error: String?=null
            main { done=condition();error=repository?.error }
            assertNull(error)
            if(done)return
            Thread.sleep(20)
        }
        fail("Timed out waiting for statistics I/O")
    }
    private fun play(day: String, id: String="track") {
        val track=Track(1,id,null,"Title, \"quoted\"","Artist")
        val wall=java.time.LocalDate.parse(day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()+60_000
        main {
            repository!!.engine.disconnect()
            for(i in 0..30) repository!!.engine.observe(Playback(3,track.title,track.artist,i*1000L,180_000,1f,1000+i*1000L,track),1000+i*1000L,wall+i*1000L)
        }
    }
    fun testPersistenceFiltersAndLocalOnlyStorage() {
        play("2026-11-09");play("2026-11-10","other")
        main { repository!!.shuffle("reroll");repository!!.refresh("2026-11-10") }
        await { repository!!.snapshot.plays==1L }
        main { assertEquals(30_000L,repository!!.snapshot.listeningMs);assertEquals(2,counts.size) }
        assertTrue(File(directory,"listening-statistics.db").isFile)
        main { repository!!.close() };open()
        main { repository!!.refresh("") }
        await { repository!!.snapshot.plays==2L }
        main { assertEquals(60_000L,repository!!.snapshot.listeningMs);assertEquals(2,repository!!.snapshot.history.size) }
    }
    fun testPauseSurvivesRestartAndClearDoesNotTouchOtherFiles() {
        play("2026-11-10")
        val unrelated=File(directory,"weights-and-logs-sentinel").apply {writeText("preserved")}
        main {repository!!.pause(true);repository!!.refresh("")}
        await {repository!!.snapshot.plays==1L}
        main {repository!!.close()};open()
        main {assertFalse(repository!!.collecting)}
        play("2026-11-10","paused")
        main {repository!!.refresh("")}
        await {repository!!.snapshot.plays==1L}
        main {repository!!.clear()}
        await {repository!!.snapshot.tracks.isEmpty() && counts.isEmpty()}
        assertEquals("preserved",unrelated.readText())
        main {repository!!.close()};open()
        main {repository!!.refresh("")}
        await {repository!!.snapshot.tracks.isEmpty()}
        main {assertFalse(repository!!.collecting);assertTrue(counts.isEmpty())}
    }
    fun testJsonExportContainsExactTotalsAndFailedDestinationDoesNotStopCollection() {
        play("2026-11-10")
        var result: String?=null
        main {repository!!.export("json") { result=it }}
        await {repository!!.exportStatus!=null}
        val json=JSONObject(result!!)
        assertEquals(30_000L,json.getJSONArray("sessions_by_day").getJSONObject(0).getLong("listening_ms"))
        main {repository!!.export("json") {throw java.io.IOException("Destination closed")}}
        await {repository!!.exportStatus?.startsWith("Export failed")==true}
        main {assertTrue(repository!!.collecting);assertNull(repository!!.error)}
    }
    fun testLearnedSmartWeightsPersistExportAndClearIndependentlyOfSavedWeights() {
        val track=Track(1,"smart",null,"Smart track","Artist")
        main {
            repository!!.configureSmartWeights(SmartWeightOptions(enabled=true))
            for(i in 0..10) repository!!.engine.observe(Playback(3,track.title,track.artist,i*1000L,10_000,1f,1000+i*1000L,track),1000+i*1000L,1_000_000+i*1000L)
        }
        await { repository!!.smartScores[track.key]==0.005 }
        main {repository!!.close()};open()
        main {assertEquals(0.005,repository!!.smartScores[track.key]!!,0.000001)}
        var result: String?=null
        main {repository!!.export("json") { result=it }}
        await {repository!!.exportStatus!=null}
        assertEquals(0.005,JSONObject(result!!).getJSONArray("smart_weights").getJSONObject(0).getDouble("score"),0.000001)
        main {repository!!.clear()}
        await {repository!!.smartScores.isEmpty()}
        main {repository!!.close()};open()
        main {assertTrue(repository!!.smartScores.isEmpty())}
    }
    fun testSmartLearningIsOptInAndPauseDoesNotBackfill() {
        val track=Track(1,"smart-off",null,"Track","Artist")
        fun complete(start: Long) { main {
            repository!!.engine.disconnect()
            for(i in 0..10) repository!!.engine.observe(Playback(3,track.title,track.artist,i*1000L,10_000,1f,start+i*1000L,track),start+i*1000L,1_000_000+i*1000L)
            repository!!.refresh("")
        } }
        complete(1000);await {repository!!.snapshot.plays==1L}
        main {assertTrue(repository!!.smartScores.isEmpty());repository!!.configureSmartWeights(SmartWeightOptions(true));repository!!.pause(true)}
        complete(20_000)
        main {repository!!.pause(false)}
        complete(40_000);await {repository!!.smartScores[track.key]==0.005}
        main {assertEquals(0.005,repository!!.smartScores[track.key]!!,0.000001)}
    }
    fun testVersionOneDatabaseMigrationPreservesListeningAndOptions() {
        play("2026-11-10")
        main {repository!!.pause(true);repository!!.refresh("")}
        await {repository!!.snapshot.plays==1L}
        main {repository!!.close()}
        // Recreate the exact pre-learning schema while preserving its listening/options rows.
        android.database.sqlite.SQLiteDatabase.openDatabase(File(directory,"listening-statistics.db").absolutePath,null,0).use { db ->
            db.execSQL("DROP TABLE smart_weights");db.execSQL("DROP TABLE smart_feedback");db.version=1
        }
        open();main {repository!!.refresh("")}
        await {repository!!.snapshot.plays==1L}
        main {assertFalse(repository!!.collecting);assertTrue(repository!!.smartScores.isEmpty())}
    }
    fun testPositiveLearningBudgetIsEnforcedAcrossDifferentSignals() {
        val track=Track(1,"budget",null,"Budget track","Artist")
        main {
            repository!!.configureSmartWeights(SmartWeightOptions(enabled=true))
            for(i in 0..10) repository!!.engine.observe(Playback(3,track.title,track.artist,i*1000L,10_000,1f,1000+i*1000L,track),1000+i*1000L,1_000_000+i*1000L)
            for(i in 0..10) {
                val p=Playback(3,track.title,track.artist,i*1000L,10_000,1f,12_000+i*1000L,track)
                repository!!.engine.observe(p,12_000+i*1000L,2_000_000+i*1000L,restarted=i==0,userReplay=i==0)
                repository!!.engine.observeVolume(p,ListeningVolume(1,2,15,if(i<6)5 else 6),12_000+i*1000L)
            }
            repository!!.refresh("")
        }
        await {repository!!.snapshot.plays==2L && (repository!!.smartScores[track.key]?:0.0)>=0.0199}
        main {assertEquals(0.02,repository!!.smartScores[track.key]!!,0.000001)}
    }
    override fun tearDown() {
        main {repository?.close()}
        super.tearDown()
    }
}
