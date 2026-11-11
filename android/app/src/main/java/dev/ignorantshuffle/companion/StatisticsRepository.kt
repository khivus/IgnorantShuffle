package dev.ignorantshuffle.companion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Private pending/cache database; portable per-device ledgers live alongside music-root weights. */
class StatisticsRepository(context: Context, private val onSmartScores: (Map<String,Double>) -> Unit = {},
    private val onCounts: (Map<String, Long>) -> Unit) : AutoCloseable {
    private val originPreferences=context.getSharedPreferences("statistics-device",Context.MODE_PRIVATE)
    private val origin=originPreferences.getString("origin",null)?:java.util.UUID.randomUUID().toString().also {
        originPreferences.edit().putString("origin",it).apply()
    }
    private val shared=SharedStatisticsStore(java.io.File(context.noBackupFilesDir,"statistics-cache"),origin)
    var sharedStatus by mutableStateOf("Choose music roots to save statistics alongside weights");private set
    fun configureRoots(roots: List<StatisticsRoot>,resolve: (String,String,String)->FileMatch) = execute {
        shared.configure(roots,resolve)
        val records=allRecords(force=true)
        publishLearning(records)
    }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val helper = object : SQLiteOpenHelper(context, java.io.File(context.noBackupFilesDir, "listening-statistics.db").absolutePath, null, 3) {
        private fun learningTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE smart_weights(track TEXT PRIMARY KEY, score REAL NOT NULL)")
            db.execSQL("CREATE TABLE smart_feedback(session TEXT, kind TEXT, delta REAL NOT NULL, requested REAL NOT NULL, updated INTEGER NOT NULL, day TEXT NOT NULL, PRIMARY KEY(session,kind))")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, track TEXT, title TEXT, artist TEXT, started INTEGER, updated INTEGER, ms INTEGER, qualified INTEGER, early INTEGER)")
            db.execSQL("CREATE TABLE slices(session TEXT, day TEXT, ms INTEGER, plays INTEGER, exits INTEGER, PRIMARY KEY(session,day))")
            db.execSQL("CREATE INDEX slices_day ON slices(day)")
            db.execSQL("CREATE TABLE shuffle(day TEXT, kind TEXT, count INTEGER, PRIMARY KEY(day,kind))")
            db.execSQL("CREATE TABLE options(name TEXT PRIMARY KEY, value INTEGER)")
            learningTables(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if(oldVersion<2) learningTables(db)
            if(oldVersion==2) {
                db.execSQL("ALTER TABLE smart_feedback ADD COLUMN requested REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE smart_feedback ADD COLUMN updated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE smart_feedback ADD COLUMN day TEXT NOT NULL DEFAULT '1970-01-01'")
                db.execSQL("UPDATE smart_feedback SET requested=delta,updated=COALESCE((SELECT updated FROM sessions WHERE id=smart_feedback.session),0)")
                db.execSQL("UPDATE smart_feedback SET day=strftime('%Y-%m-%d',updated/1000,'unixepoch','localtime')")
            }
        }
    }
    var ready by mutableStateOf(false); private set
    var collecting by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var snapshot by mutableStateOf(StatisticsSnapshot()); private set
    var exportStatus by mutableStateOf<String?>(null); private set
    var actionSequence by mutableStateOf(0L); private set
    var actionStatus by mutableStateOf(""); private set
    private fun completed(text: String) { actionStatus = text; actionSequence++ }
    private var generation = 0L
    private var lastLibraryFlush=0L
    private var counterEpoch=""
    @Volatile private var smartOptions=SmartWeightOptions()
    var smartScores by mutableStateOf(emptyMap<String,Double>()); private set
    val engine = ListeningStatistics(feedback=::learn, emit=::record)
    fun configureSmartWeights(options: SmartWeightOptions) {
        options.validate();smartOptions=options;engine.volumeRouteChanged()
        if(ready)execute {publishLearning(allRecords())}
    }

    init { execute {
        val enabled = helper.readableDatabase.rawQuery("SELECT value FROM options WHERE name='collecting'", null).use { !it.moveToFirst() || it.getInt(0) != 0 }
        counterEpoch=helper.readableDatabase.rawQuery("SELECT value FROM options WHERE name='shuffle_epoch'",null).use {if(it.moveToFirst())it.getString(0) else java.util.UUID.randomUUID().toString()}
        helper.writableDatabase.execSQL("INSERT OR REPLACE INTO options VALUES('shuffle_epoch',?)",arrayOf<Any>(counterEpoch))
        val counts = counts()
        val scores=scores()
        main.post { collecting = enabled; engine.collecting(enabled); ready = true; smartScores=scores; onSmartScores(scores); onCounts(counts) }
    } }

    private fun execute(storage: Boolean = true, block: () -> Unit) { io.execute {
        try { block() } catch (e: Exception) { main.post {
            if (storage) { error = e.message ?: "Statistics storage failed"; collecting = false; engine.collecting(false) }
            else exportStatus = "Export failed: ${e.message}"
        } }
    } }
    private fun record(delta: ListeningDelta) = execute {
        val s = delta.session; val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR REPLACE INTO sessions VALUES(?,?,?,?,?,?,?,?,?)", arrayOf<Any>(s.id,s.key,s.title,s.artist,s.started,s.updated,s.listeningMs,if(s.qualified)1 else 0,if(s.earlyExit)1 else 0))
            db.execSQL("INSERT OR IGNORE INTO slices VALUES(?,?,0,0,0)", arrayOf<Any>(s.id,delta.day))
            db.execSQL("UPDATE slices SET ms=ms+?, plays=plays+?, exits=exits+? WHERE session=? AND day=?", arrayOf<Any>(delta.ms,delta.plays,delta.exits,s.id,delta.day))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if(delta.plays>0 || System.currentTimeMillis()-lastLibraryFlush>=15000) {
            lastLibraryFlush=System.currentTimeMillis();publishLearning(allRecords())
        }
    }
    private fun localRecords(): List<PortableStat> = buildList {
        val db=helper.readableDatabase
        db.rawQuery("SELECT s.id,s.track,s.title,s.artist,s.started,s.updated,d.day,d.ms,d.plays,d.exits FROM sessions s JOIN slices d ON s.id=d.session",null).use {c ->
            while(c.moveToNext())add(PortableStat(c.getString(0)+"/l/"+c.getString(6),kind="L",key=c.getString(1),title=c.getString(2),artist=c.getString(3),session=c.getString(0),day=c.getString(6),started=c.getLong(4),updated=c.getLong(5),ms=c.getLong(7),plays=c.getLong(8),exits=c.getLong(9)))
        }
        db.rawQuery("SELECT f.session,s.track,s.title,s.artist,s.started,f.updated,f.day,f.kind,f.requested FROM smart_feedback f JOIN sessions s ON s.id=f.session",null).use {c ->
            while(c.moveToNext())add(PortableStat(c.getString(0)+"/f/"+c.getString(7),kind="F",key=c.getString(1),title=c.getString(2),artist=c.getString(3),session=c.getString(0),day=c.getString(6),started=c.getLong(4),updated=c.getLong(5),value=c.getDouble(8),label=c.getString(7)))
        }
        db.rawQuery("SELECT day,kind,count FROM shuffle",null).use {c ->
            while(c.moveToNext())add(PortableStat("$origin/$counterEpoch/c/${c.getString(0)}/${c.getString(1)}",kind="C",key="@shuffle",session="counter:$origin:$counterEpoch",day=c.getString(0),value=c.getLong(2).toDouble(),label=c.getString(1)))
        }
    }
    private fun allRecords(force: Boolean=false): List<PortableStat> {
        val records=shared.sync(localRecords(),force)
        val status=shared.status;main.post {sharedStatus=status}
        return records
    }
    private fun counts(): Map<String,Long> = allRecords().filter {it.kind=="L"}.groupBy {it.key}.mapValues {(_,rows)->rows.sumOf {it.plays}}
    private fun scores(): Map<String,Double> = PortableStatistics.scores(allRecords(),smartOptions.limit)
    private fun publishLearning(records: List<PortableStat>) {
        val counts=records.filter {it.kind=="L"}.groupBy {it.key}.mapValues {(_,rows)->rows.sumOf {it.plays}}
        val scores=PortableStatistics.scores(records,smartOptions.limit)
        main.post {smartScores=scores;onSmartScores(scores);onCounts(counts)}
    }
    private fun learn(event: SmartFeedback) {
        val options=smartOptions
        if(!ready || !collecting || error!=null || options.step(event.kind)==0.0)return
        execute {
            val db=helper.writableDatabase
            db.beginTransaction()
            try {
                val duplicate=db.rawQuery("SELECT 1 FROM smart_feedback WHERE session=? AND kind=?",arrayOf(event.session,event.kind.name)).use { it.moveToFirst() }
                if(!duplicate) {
                    val old=db.rawQuery("SELECT score FROM smart_weights WHERE track=?",arrayOf(event.key)).use { if(it.moveToFirst())it.getDouble(0) else 0.0 }
                    val gain=db.rawQuery("SELECT COALESCE(SUM(MAX(requested,0)),0) FROM smart_feedback WHERE session=?",arrayOf(event.session)).use { it.moveToFirst();it.getDouble(0) }
                    val delta=options.adjustment(old,event.kind,gain)
                    val requested=options.step(event.kind).let {if(it>0)minOf(it,(0.015-gain).coerceAtLeast(0.0)) else it}
                    val wall=event.wall.takeIf {it>0}?:System.currentTimeMillis()
                    db.execSQL("INSERT INTO smart_feedback VALUES(?,?,?,?,?,?)",arrayOf<Any>(event.session,event.kind.name,delta,requested,wall,event.day.ifBlank {ListeningStatistics.day(wall)}))
                    db.execSQL("INSERT OR REPLACE INTO smart_weights VALUES(?,?)",arrayOf<Any>(event.key,old+delta))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val scores=scores()
            main.post { smartScores=scores;onSmartScores(scores) }
        }
    }
    fun pause(paused: Boolean) {
        if (!ready) return
        collecting = !paused; engine.collecting(!paused); completed(if(paused) "Statistics collection paused" else "Statistics collection resumed")
        execute { helper.writableDatabase.execSQL("INSERT OR REPLACE INTO options VALUES('collecting',?)", arrayOf<Any>(if(paused)0 else 1)) }
    }
    fun flushShared() = execute {publishLearning(allRecords(force=true))}
    fun clear() {
        generation++
        engine.reset()
        execute {
            allRecords(force=true);shared.clear()
            counterEpoch=java.util.UUID.randomUUID().toString()
            val db=helper.writableDatabase
            db.beginTransaction()
            try { listOf("slices","sessions","shuffle","smart_weights","smart_feedback").forEach { db.delete(it,null,null) }
                db.execSQL("INSERT OR REPLACE INTO options VALUES('shuffle_epoch',?)",arrayOf<Any>(counterEpoch));db.setTransactionSuccessful() }
            finally { db.endTransaction() }
            main.post { completed("Statistics cleared"); snapshot=StatisticsSnapshot(); smartScores=emptyMap(); onSmartScores(emptyMap()); onCounts(emptyMap()) }
        }
    }
    fun shuffle(kind: String) {
        if (!ready || !collecting || error != null) return
        val day=ListeningStatistics.day(System.currentTimeMillis())
        execute {
            allRecords(force=true)
            val db=helper.writableDatabase;db.beginTransaction()
            try {
                if(shared.cleared("$origin/$counterEpoch/c/$day/$kind")) {
                    counterEpoch=java.util.UUID.randomUUID().toString()
                    db.execSQL("INSERT OR REPLACE INTO options VALUES('shuffle_epoch',?)",arrayOf<Any>(counterEpoch))
                    db.delete("shuffle",null,null)
                }
                db.execSQL("INSERT OR IGNORE INTO shuffle VALUES(?,?,0)",arrayOf<Any>(day,kind))
                db.execSQL("UPDATE shuffle SET count=count+1 WHERE day=? AND kind=?",arrayOf<Any>(day,kind))
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
        }
    }
    fun refresh(since: String) {
        val requested = ++generation
        execute {
        val records=allRecords()
        val selected=records.filter {it.day>=since}
        val tracks=selected.filter {it.kind=="L"}.groupBy {it.key}.map { (key,rows) ->
            TrackStatistics(key,rows.last().title,rows.last().artist,rows.sumOf {it.ms},rows.sumOf {it.plays},rows.sumOf {it.exits})
        }.sortedWith(compareByDescending<TrackStatistics> {it.plays}.thenByDescending {it.ms})
        val history=selected.filter {it.kind=="L"}.groupBy {it.session}.map { (id,rows) ->
            val r=rows.maxBy {it.updated}
            ListeningSession(id,r.key,r.title,r.artist,rows.minOf {it.started},r.updated,rows.sumOf {it.ms},rows.any {it.plays>0},rows.any {it.exits>0})
        }.sortedByDescending {it.updated}.take(200)
        val days=selected.filter {it.kind=="L"}.groupBy {it.day}.toSortedMap().mapValues {(_,rows)->rows.sumOf {it.ms}}
        val shuffle=selected.filter {it.kind=="C"}.groupBy {it.label}.mapValues {(_,rows)->rows.sumOf {it.value.toLong()}}
        publishLearning(records)
        main.post { if (generation == requested) snapshot=StatisticsSnapshot(tracks,history,days,shuffle) }
        }
    }

    /** Export all retained session/day totals and shuffle counters, independent of the screen filter. */
    fun export(format: String, write: (String) -> Unit) = execute(storage = false) {
        val rows=JSONArray();val counters=JSONArray();val records=allRecords(force=true)
        records.filter {it.kind=="L"}.sortedWith(compareBy<PortableStat> {it.started}.thenBy {it.day}).forEach {r ->
            rows.put(JSONObject().put("session",r.session).put("track",r.key).put("title",r.title).put("artist",r.artist).put("started",r.started).put("updated",r.updated).put("day",r.day).put("listening_ms",r.ms).put("qualified_plays",r.plays).put("early_exits",r.exits))
        }
        records.filter {it.kind=="C"}.groupBy {it.day to it.label}.forEach { (key,values) -> counters.put(JSONObject().put("day",key.first).put("kind",key.second).put("count",values.sumOf {it.value.toLong()})) }
        val learned=JSONArray()
        PortableStatistics.scores(records,smartOptions.limit).forEach { (key,score) -> learned.put(JSONObject().put("track",key).put("score",score)) }
        write(StatisticsExport.encode(format, rows, counters, learned))
        main.post { exportStatus = "${format.uppercase()} export saved"; completed(requireNotNull(exportStatus)) }
    }
    override fun close() { engine.disconnect();io.execute {try {allRecords(force=true)} finally {helper.close()}};io.shutdown() }
}

object StatisticsExport {
    fun encode(format: String, rows: JSONArray, counters: JSONArray, learned: JSONArray=JSONArray()): String {
        if(format=="json") return JSONObject().put("schema",1).put("sessions_by_day",rows).put("shuffle",counters).put("smart_weights",learned).toString(2)
        val keys=listOf("session","track","title","artist","started","updated","day","listening_ms","qualified_plays","early_exits")
        return buildString {
            appendLine((listOf("type")+keys+listOf("kind","count","score")).joinToString(","))
            for(i in 0 until rows.length()) appendLine((listOf("listening")+keys.map { rows.getJSONObject(i).get(it).toString() }+listOf("","","")).joinToString(",",transform=::csv))
            for(i in 0 until counters.length()) {
                val c=counters.getJSONObject(i)
                appendLine((listOf("shuffle")+keys.map { if(it=="day")c.getString("day") else "" }+listOf(c.getString("kind"),c.getLong("count").toString(),"")).joinToString(",",transform=::csv))
            }
            for(i in 0 until learned.length()) {
                val row=learned.getJSONObject(i)
                appendLine((listOf("smart_weight")+keys.map { if(it=="track")row.getString("track") else "" }+listOf("","",row.getDouble("score").toString())).joinToString(",",transform=::csv))
            }
        }
    }
    private fun csv(value: String) = "\""+value.replace("\"","\"\"")+"\""
}
