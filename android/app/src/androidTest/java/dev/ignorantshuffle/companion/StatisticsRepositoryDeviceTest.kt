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
    fun testVersionTwoMigrationKeepsLearningAndExportsBesideWeights() {
        val track=Track(1,"v2-smart",null,"Legacy","Artist")
        main {
            repository!!.configureSmartWeights(SmartWeightOptions(enabled=true))
            for(i in 0..10)repository!!.engine.observe(Playback(3,track.title,track.artist,i*1000L,10000,1f,1000+i*1000L,track),1000+i*1000L,1_000_000+i*1000L)
        }
        await {repository!!.smartScores[track.key]==.005}
        main {repository!!.close()}
        android.database.sqlite.SQLiteDatabase.openDatabase(File(directory,"listening-statistics.db").absolutePath,null,0).use {db ->
            db.execSQL("ALTER TABLE smart_feedback RENAME TO feedback_v3")
            db.execSQL("CREATE TABLE smart_feedback(session TEXT,kind TEXT,delta REAL NOT NULL,PRIMARY KEY(session,kind))")
            db.execSQL("INSERT INTO smart_feedback SELECT session,kind,delta FROM feedback_v3")
            db.execSQL("DROP TABLE feedback_v3");db.version=2
        }
        open()
        val root=File(directory,"music").apply {mkdirs()}
        val documents=object : WeightDocuments {
            override fun names()=root.list()?.toList()?:emptyList()
            override fun find(name: String)=name.takeIf {File(root,it).isFile}
            override fun read(id: String)=File(root,id).readText()
            override fun create(name: String): String {check(File(root,name).createNewFile());return name}
            override fun write(id: String,text: String)=File(root,id).writeText(text)
            override fun rename(id: String,name: String): String {check(File(root,id).renameTo(File(root,name)));return name}
            override fun delete(id: String) {check(File(root,id).delete())}
        }
        val foreign=PortableStatistics().apply {put(PortableStat("windows/l/1970-01-01",kind="L",key="Artist/Legacy.flac",title="Legacy",artist="Artist",session="windows",day="1970-01-01",started=2000000,updated=2030000,ms=30000,plays=1))}
        File(root,PortableStatistics.filename("windows")).writeText(foreign.text())
        File(root,PortableWeights.FILENAME).writeText("weight sentinel")
        main {repository!!.configureRoots(listOf(StatisticsRoot("music",documents))) {_,_,_->FileMatch("Artist/Legacy.flac","Migrated","music")};repository!!.refresh("")}
        await {repository!!.snapshot.plays==2L}
        val identity=PortableStatistics.identity("music","Artist/Legacy.flac")
        var learned=0.0;var total=0L
        main {learned=repository!!.smartScores[identity]?:0.0;total=repository!!.snapshot.listeningMs}
        assertEquals(.005,learned,.000001);assertEquals(40000L,total)
        assertEquals("weight sentinel",File(root,PortableWeights.FILENAME).readText())
        assertEquals(2,root.listFiles()!!.count {PortableStatistics.shard(it.name)})
        main {repository!!.close()};open()
        main {repository!!.configureRoots(listOf(StatisticsRoot("music",documents))) {_,_,_->FileMatch("Artist/Legacy.flac","Migrated","music")};repository!!.refresh("")}
        await {repository!!.snapshot.plays==2L}
        main {repository!!.clear()}
        await {repository!!.snapshot.tracks.isEmpty()}
        val ledgers=PortableStatistics();root.listFiles()!!.filter {PortableStatistics.shard(it.name)}.forEach {ledgers.merge(PortableStatistics.parse(it.readText()))}
        assertTrue(ledgers.active().isEmpty())
        main {repository!!.shuffle("reroll");repository!!.refresh("")}
        await {repository!!.snapshot.shuffle["reroll"]==1L}
        main {repository!!.flushShared()}
        // Another device clears the known counter; the next local reroll starts fresh.
        await {root.listFiles()!!.filter {PortableStatistics.shard(it.name)}.any {file ->PortableStatistics.parse(file.readText()).active().any {it.kind=="C"}}}
        val remote=PortableStatistics();root.listFiles()!!.filter {PortableStatistics.shard(it.name)}.forEach {remote.merge(PortableStatistics.parse(it.readText()))}
        remote.clear("windows",System.currentTimeMillis())
        File(root,PortableStatistics.filename("windows")).writeText(remote.text())
        main {repository!!.shuffle("reroll");repository!!.refresh("")}
        await {repository!!.snapshot.shuffle["reroll"]==1L}
        main {repository!!.flushShared()}
        await {root.listFiles()!!.filter {PortableStatistics.shard(it.name)}.map {PortableStatistics.parse(it.readText())}.let {all ->PortableStatistics().apply {all.forEach(::merge)}.active().sumOf {if(it.kind=="C")it.value.toLong() else 0L}==1L}}
        assertEquals("weight sentinel",File(root,PortableWeights.FILENAME).readText())
    }
    fun testNativeLearningUsesSameRulesAsAndroidReference() {
        val a=Track(1,"a",null,"A","Artist");val b=Track(2,"b",null,"B","Artist")
        val planner=Planner {_,_,_,_,_,_->Choice(null)}
        val reference=ShuffleModel(planner);val native=ShuffleModel(planner,nativeLearning=true)
        for(strength in listOf(.25,.5,1.0))for(cap in listOf(.05,.15,.25))for(base in listOf(0.0,.5,1.0,5.0,25.0)) {
            for(model in listOf(reference,native)) {
                model.catalog(listOf(a,b));model.configure(Settings(),mapOf(a.key to base))
                model.balance(true,mapOf(a.key to 10L,b.key to 0L),strength)
                model.smartWeights(SmartWeightOptions(enabled=true,limit=cap),mapOf(a.key to .1,b.key to -.1))
            }
            assertEquals(reference.selectionWeight(a),native.selectionWeight(a),.0000001)
            assertEquals(reference.selectionWeight(b),native.selectionWeight(b),.0000001)
        }
    }
    override fun tearDown() {
        main {repository?.close()}
        super.tearDown()
    }
}
