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
    override fun tearDown() {
        main {repository?.close()}
        super.tearDown()
    }
}
