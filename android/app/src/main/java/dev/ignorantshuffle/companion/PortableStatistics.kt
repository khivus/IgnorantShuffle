package dev.ignorantshuffle.companion

import java.time.LocalDate
import java.util.Base64
import java.util.Locale

data class PortableStat(val id: String, val revision: Long=1, val kind: String, val key: String,
    val title: String="", val artist: String="", val session: String="", val day: String,
    val started: Long=0, val updated: Long=0, val ms: Long=0, val plays: Long=0,
    val exits: Long=0, val value: Double=0.0, val label: String="") {
    fun validate() {
        require(id.isNotEmpty() && id.length<=1024 && revision>0 && key.isNotEmpty() && key.toByteArray(Charsets.UTF_8).size<=65536) {"Invalid statistics identity"}
        require(kind in listOf("L","F","C","X") && value.isFinite() && started>=0 && updated>=0 && ms>=0 && plays in 0..1 && exits in 0..1) {"Invalid statistics record"}
        require(kind!="C" || (value>=0 && value<=9e15 && kotlin.math.floor(value)==value))
        require(kind!="F" || kotlin.math.abs(value)<=1)
        require(day.length==10);require(LocalDate.parse(day).year>=1)
        require(kind=="X" || PortableWeights.relative(key)) {"Statistics need a root-relative path"}
    }
}
class PortableStatistics {
    val records=linkedMapOf<String,PortableStat>()
    fun merge(record: PortableStat) {
        record.validate()
        val old=records[record.id]
        require(old==null || old.revision!=record.revision || old==record) {"Conflicting statistics revision; files preserved"}
        if(old==null || record.revision>old.revision)records[record.id]=record
    }
    fun merge(other: PortableStatistics) {other.records.values.forEach(::merge)}
    fun put(record: PortableStat) {
        val old=records[record.id]
        if(old!=null && record.copy(revision=old.revision)==old)return
        merge(record.copy(revision=old?.revision?.let {Math.addExact(it,1)}?:record.revision))
    }
    fun active(): List<PortableStat> {
        val cleared=records.values.filter {it.kind=="X"}.map {it.key}.toSet()
        return records.values.filter {it.kind!="X" && it.id !in cleared}
    }
    fun clear(origin: String,wall: Long) {active().forEach {r -> put(PortableStat("clear:$origin:$wall:${r.id}",kind="X",key=r.id,day=r.day,started=wall,updated=wall))}}
    fun text(): String = buildString {
        appendLine(HEADER)
        records.toSortedMap().values.forEach {r -> appendLine(listOf(hex(r.id),r.revision,r.kind,hex(r.key),hex(r.title),hex(r.artist),hex(r.session),r.day,r.started,r.updated,r.ms,r.plays,r.exits,r.value,hex(r.label)).joinToString("\t"))}
    }
    companion object {
        const val HEADER="# IgnorantShuffle statistics v1"
        fun filename(origin: String): String {
            require(origin.matches(Regex("[a-zA-Z0-9-]{1,128}")))
            return "IgnorantShuffle.statistics.$origin.tsv"
        }
        fun shard(name: String)=name.startsWith("IgnorantShuffle.statistics.") && name.endsWith(".tsv")
        fun hex(text: String)=text.toByteArray(Charsets.UTF_8).joinToString("") {"%02x".format(Locale.ROOT,it.toInt() and 255)}
        fun unhex(text: String): String {
            require(text.length%2==0 && text.all {it in '0'..'9' || it in 'a'..'f'})
            val bytes=ByteArray(text.length/2) {text.substring(it*2,it*2+2).toInt(16).toByte()}
            return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().also {require('\u0000' !in it)}
        }
        fun parse(text: String): PortableStatistics {
            require(text.toByteArray(Charsets.UTF_8).size<=32*1024*1024) {"Statistics shard is too large"}
            val lines=text.lineSequence().iterator();require(lines.hasNext() && lines.next().removeSuffix("\r")==HEADER) {"Unsupported statistics schema"}
            return PortableStatistics().apply {while(lines.hasNext()) {
                val line=lines.next().removeSuffix("\r");if(line.isEmpty() || line.startsWith('#'))continue
                val f=line.split('\t');require(f.size==15) {"Invalid statistics columns"}
                merge(PortableStat(unhex(f[0]),f[1].toLong(),f[2],unhex(f[3]),unhex(f[4]),unhex(f[5]),unhex(f[6]),f[7],f[8].toLong(),f[9].toLong(),f[10].toLong(),f[11].toLong(),f[12].toLong(),f[13].toDouble(),unhex(f[14])))
            }}
        }
        fun identity(root: String,path: String): String = "shared:"+Base64.getUrlEncoder().withoutPadding().encodeToString(root.toByteArray(Charsets.UTF_8))+":"+Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))
        fun location(key: String): Pair<String,String>? = runCatching {
            if(!key.startsWith("shared:"))return null
            val p=key.split(':');require(p.size==3)
            String(Base64.getUrlDecoder().decode(p[1]),Charsets.UTF_8) to String(Base64.getUrlDecoder().decode(p[2]),Charsets.UTF_8)
        }.getOrNull()
        fun scores(records: List<PortableStat>,limit: Double): Map<String,Double> {
            val scores=mutableMapOf<String,Double>();val budgets=mutableMapOf<String,Double>()
            records.filter {it.kind=="F"}.sortedWith(compareBy<PortableStat> {it.updated}.thenBy {it.id}).forEach {r ->
                var step=r.value
                if(step>0 && r.label!="migration") {step=minOf(step,(0.015-(budgets[r.session]?:0.0)).coerceAtLeast(0.0));budgets[r.session]=(budgets[r.session]?:0.0)+step}
                scores[r.key]=((scores[r.key]?:0.0)+step).coerceIn(-limit,limit)
            }
            return scores
        }
    }
}

/** Atomic per-device sidecar writes; weights and other devices' shards are never replaced. */
object SharedStatisticsFile {
    fun save(documents: WeightDocuments,name: String,ledger: PortableStatistics) {
        val backup="$name.bak";check(documents.find(backup)==null) {"Statistics backup needs recovery; files preserved"}
        val text=ledger.text()
        val old=documents.find(name)
        if(old!=null && documents.read(old)==text)return
        var temporary: String?=documents.create("$name.tmp-${java.util.UUID.randomUUID()}")
        var saved: String?=null
        try {
            documents.write(requireNotNull(temporary),text)
            require(PortableStatistics.parse(documents.read(requireNotNull(temporary))).records==ledger.records)
            if(old!=null)saved=documents.rename(old,backup)
            documents.rename(requireNotNull(temporary),name);temporary=null
            saved?.let {documents.delete(it)};saved=null
        } catch(e: Exception) {
            if(saved!=null && documents.find(name)==null)runCatching {documents.rename(requireNotNull(saved),name)}
            throw e
        } finally {temporary?.let {runCatching {documents.delete(it)}}}
    }
}
