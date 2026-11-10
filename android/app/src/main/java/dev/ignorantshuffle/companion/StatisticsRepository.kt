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

/** Dedicated private database in noBackupFilesDir. No weights, log, sync, network or backup path. */
class StatisticsRepository(context: Context, private val onSmartScores: (Map<String,Double>) -> Unit = {},
    private val onCounts: (Map<String, Long>) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val helper = object : SQLiteOpenHelper(context, java.io.File(context.noBackupFilesDir, "listening-statistics.db").absolutePath, null, 2) {
        private fun learningTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE smart_weights(track TEXT PRIMARY KEY, score REAL NOT NULL)")
            db.execSQL("CREATE TABLE smart_feedback(session TEXT, kind TEXT, delta REAL NOT NULL, PRIMARY KEY(session,kind))")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, track TEXT, title TEXT, artist TEXT, started INTEGER, updated INTEGER, ms INTEGER, qualified INTEGER, early INTEGER)")
            db.execSQL("CREATE TABLE slices(session TEXT, day TEXT, ms INTEGER, plays INTEGER, exits INTEGER, PRIMARY KEY(session,day))")
            db.execSQL("CREATE INDEX slices_day ON slices(day)")
            db.execSQL("CREATE TABLE shuffle(day TEXT, kind TEXT, count INTEGER, PRIMARY KEY(day,kind))")
            db.execSQL("CREATE TABLE options(name TEXT PRIMARY KEY, value INTEGER)")
            learningTables(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion<2) learningTables(db) }
    }
    var ready by mutableStateOf(false); private set
    var collecting by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var snapshot by mutableStateOf(StatisticsSnapshot()); private set
    var exportStatus by mutableStateOf<String?>(null); private set
    private var generation = 0L
    private var smartOptions=SmartWeightOptions()
    var smartScores by mutableStateOf(emptyMap<String,Double>()); private set
    val engine = ListeningStatistics(feedback=::learn, emit=::record)
    fun configureSmartWeights(options: SmartWeightOptions) {
        options.validate();smartOptions=options;engine.volumeRouteChanged()
    }

    init { execute {
        val enabled = helper.readableDatabase.rawQuery("SELECT value FROM options WHERE name='collecting'", null).use { !it.moveToFirst() || it.getInt(0) != 0 }
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
        if (delta.plays > 0) { val counts = counts(); main.post { onCounts(counts) } }
    }
    private fun counts(): Map<String,Long> = buildMap {
        helper.readableDatabase.rawQuery("SELECT track,SUM(qualified) FROM sessions GROUP BY track",null).use { c -> while(c.moveToNext()) put(c.getString(0),c.getLong(1)) }
    }
    private fun scores(): Map<String,Double> = buildMap {
        helper.readableDatabase.rawQuery("SELECT track,score FROM smart_weights",null).use { c -> while(c.moveToNext())put(c.getString(0),c.getDouble(1)) }
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
                    val gain=db.rawQuery("SELECT COALESCE(SUM(MAX(delta,0)),0) FROM smart_feedback WHERE session=?",arrayOf(event.session)).use { it.moveToFirst();it.getDouble(0) }
                    val delta=options.adjustment(old,event.kind,gain)
                    db.execSQL("INSERT INTO smart_feedback VALUES(?,?,?)",arrayOf<Any>(event.session,event.kind.name,delta))
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
        collecting = !paused; engine.collecting(!paused)
        execute { helper.writableDatabase.execSQL("INSERT OR REPLACE INTO options VALUES('collecting',?)", arrayOf<Any>(if(paused)0 else 1)) }
    }
    fun clear() {
        generation++
        engine.reset()
        execute {
            val db=helper.writableDatabase
            db.beginTransaction()
            try { listOf("slices","sessions","shuffle","smart_weights","smart_feedback").forEach { db.delete(it,null,null) }; db.setTransactionSuccessful() }
            finally { db.endTransaction() }
            main.post { snapshot=StatisticsSnapshot(); smartScores=emptyMap(); onSmartScores(emptyMap()); onCounts(emptyMap()) }
        }
    }
    fun shuffle(kind: String) {
        if (!ready || !collecting || error != null) return
        val day=ListeningStatistics.day(System.currentTimeMillis())
        execute {
            helper.writableDatabase.execSQL("INSERT OR IGNORE INTO shuffle VALUES(?,?,0)",arrayOf<Any>(day,kind))
            helper.writableDatabase.execSQL("UPDATE shuffle SET count=count+1 WHERE day=? AND kind=?",arrayOf<Any>(day,kind))
        }
    }
    fun refresh(since: String) {
        val requested = ++generation
        execute {
        val db=helper.readableDatabase
        val tracks=mutableListOf<TrackStatistics>(); val history=mutableListOf<ListeningSession>()
        val days=linkedMapOf<String,Long>(); val shuffle=linkedMapOf<String,Long>()
        db.rawQuery("SELECT s.track,s.title,s.artist,SUM(d.ms),SUM(d.plays),SUM(d.exits) FROM slices d JOIN sessions s ON s.id=d.session WHERE d.day>=? GROUP BY s.track ORDER BY SUM(d.plays) DESC,SUM(d.ms) DESC",arrayOf(since)).use { c ->
            while(c.moveToNext()) tracks.add(TrackStatistics(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getLong(5)))
        }
        db.rawQuery("SELECT s.id,s.track,s.title,s.artist,s.started,s.updated,SUM(d.ms),SUM(d.plays),SUM(d.exits) FROM sessions s JOIN slices d ON s.id=d.session WHERE d.day>=? GROUP BY s.id ORDER BY s.updated DESC LIMIT 200",arrayOf(since)).use { c ->
            while(c.moveToNext()) history.add(ListeningSession(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getLong(6),c.getLong(7)>0,c.getLong(8)>0))
        }
        db.rawQuery("SELECT day,SUM(ms) FROM slices WHERE day>=? GROUP BY day ORDER BY day",arrayOf(since)).use { c -> while(c.moveToNext()) days[c.getString(0)]=c.getLong(1) }
        db.rawQuery("SELECT kind,SUM(count) FROM shuffle WHERE day>=? GROUP BY kind",arrayOf(since)).use { c -> while(c.moveToNext()) shuffle[c.getString(0)]=c.getLong(1) }
        main.post { if (generation == requested) snapshot=StatisticsSnapshot(tracks,history,days,shuffle) }
        }
    }

    /** Export all retained session/day totals and shuffle counters, independent of the screen filter. */
    fun export(format: String, write: (String) -> Unit) = execute(storage = false) {
        val rows=JSONArray(); val counters=JSONArray()
        helper.readableDatabase.rawQuery("SELECT s.id,s.track,s.title,s.artist,s.started,s.updated,d.day,d.ms,d.plays,d.exits FROM sessions s JOIN slices d ON s.id=d.session ORDER BY s.started,d.day",null).use { c ->
            while(c.moveToNext()) rows.put(JSONObject().apply {
                listOf("session","track","title","artist","started","updated","day","listening_ms","qualified_plays","early_exits").forEachIndexed { i,k ->
                    put(k,if(i in 4..5 || i in 7..9)c.getLong(i) else c.getString(i))
                }
            })
        }
        helper.readableDatabase.rawQuery("SELECT day,kind,count FROM shuffle ORDER BY day,kind",null).use { c ->
            while(c.moveToNext()) counters.put(JSONObject().put("day",c.getString(0)).put("kind",c.getString(1)).put("count",c.getLong(2)))
        }
        val learned=JSONArray()
        scores().forEach { (key,score) -> learned.put(JSONObject().put("track",key).put("score",score)) }
        write(StatisticsExport.encode(format, rows, counters, learned))
        main.post { exportStatus = "${format.uppercase()} export saved" }
    }
    override fun close() { engine.disconnect(); io.execute { helper.close() }; io.shutdown() }
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
