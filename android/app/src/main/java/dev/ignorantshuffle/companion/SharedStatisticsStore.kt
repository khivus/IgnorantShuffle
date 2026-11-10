package dev.ignorantshuffle.companion

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class StatisticsRoot(val id: String,val documents: WeightDocuments)

/** Serialized worker-only cache. Portable sidecars are authoritative; SQLite retains pending local observations. */
class SharedStatisticsStore(private val directory: File,private val origin: String) {
    private data class Root(val id: String,var documents: WeightDocuments?,val ledger: PortableStatistics,val own: PortableStatistics)
    private val roots=linkedMapOf<String,Root>()
    private var resolver: (String,String,String)->FileMatch = {_,_,_->FileMatch(null,"No music roots")}
    private var lastSync=0L
    var status="Choose music roots to save statistics alongside weights";private set
    private fun cache(id: String,own: Boolean): File {
        val digest=MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
        return File(directory,"${if(own)"own" else "merged"}-$digest.tsv")
    }
    private fun cached(id: String,own: Boolean)=cache(id,own).let {if(it.exists())PortableStatistics.parse(it.readText(Charsets.UTF_8)) else PortableStatistics()}
    private fun persist(root: Root) {
        directory.mkdirs()
        for((own,ledger) in listOf(true to root.own,false to root.ledger)) {
            val target=cache(root.id,own);val temporary=File(target.path+".tmp")
            temporary.writeText(ledger.text(),Charsets.UTF_8)
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        }
    }
    fun configure(value: List<StatisticsRoot>,resolve: (String,String,String)->FileMatch) {
        roots.values.forEach {it.documents=null}
        value.forEach {next ->
            val root=roots.getOrPut(next.id) {Root(next.id,next.documents,cached(next.id,false),cached(next.id,true))}
            root.documents=next.documents;root.ledger.merge(root.own)
        }
        resolver=resolve;lastSync=0
    }
    private fun locations(): Map<String,Pair<String,String>> = buildMap {
        roots.values.forEach {root ->root.ledger.records.values.filter {it.kind!="X" && it.session.isNotBlank()}.forEach {putIfAbsent(it.session,root.id to it.key)}}
    }
    private fun stage(local: List<PortableStat>): List<PortableStat> {
        val locations=locations().toMutableMap()
        val known=PortableStatistics().also {all ->roots.values.forEach {all.merge(it.ledger)}}
        val cleared=known.records.values.filter {it.kind=="X"}.map {it.key}.toSet()
        val pending=mutableListOf<PortableStat>()
        local.forEach {r ->
            if(r.id in cleared)return@forEach
            val match=PortableStatistics.location(r.key)?.let {FileMatch(it.second,"Portable identity",it.first)}?:resolver(r.key,r.title,r.artist)
            val location=locations[r.session]?:match.path?.let {match.root to it}
                ?:if(r.kind=="C")roots.values.firstOrNull {it.documents!=null}?.let {it.id to "@shuffle"} else null
            val root=location?.first?.let {roots[it]}
            if(root==null || !PortableWeights.relative(requireNotNull(location).second)) {pending.add(r);return@forEach}
            if(r.session.isNotBlank())locations[r.session]=location
            val portable=r.copy(key=location.second)
            root.own.put(portable);root.ledger.merge(requireNotNull(root.own.records[r.id]))
        }
        return pending
    }
    fun records(local: List<PortableStat>): List<PortableStat> {
        val pending=stage(local)
        val combined=PortableStatistics();val owner=mutableMapOf<String,String>()
        roots.values.forEach {root ->root.ledger.records.values.forEach {r ->
            if((combined.records[r.id]?.revision?:0)<r.revision)owner[r.id]=root.id
            combined.merge(r)
        }}
        return combined.active().map {r ->r.copy(key=PortableStatistics.identity(requireNotNull(owner[r.id]),r.key))}+pending
    }
    fun sync(local: List<PortableStat>,force: Boolean=false): List<PortableStat> {
        val now=System.currentTimeMillis()
        if(force || now-lastSync>=15_000) {
            val ownName=PortableStatistics.filename(origin)
            val errors=mutableListOf<String>();val blocked=mutableSetOf<String>()
            roots.values.forEach {root ->root.documents?.let {documents ->try {
                val merged=PortableStatistics().apply {merge(root.ledger)}
                val own=PortableStatistics().apply {merge(root.own)}
                check(documents.find(ownName)!=null || documents.find("$ownName.bak")==null) {"Statistics backup needs recovery"}
                documents.names().filter(PortableStatistics::shard).forEach {name ->
                    val ledger=PortableStatistics.parse(documents.read(requireNotNull(documents.find(name))))
                    merged.merge(ledger);if(name==ownName)own.merge(ledger)
                }
                root.ledger.merge(merged);root.own.merge(own)
            } catch(e: Exception) {blocked.add(root.id);errors.add(e.message?:"Cannot read shared statistics")} }}
            lastSync=now
            val result=records(local)
            roots.values.forEach {root ->
                persist(root)
                root.documents?.let {documents ->if(root.own.records.isNotEmpty() && root.id !in blocked)try {
                    SharedStatisticsFile.save(documents,ownName,root.own)
                } catch(e: Exception) {errors.add(e.message?:"Cannot save shared statistics")} }
            }
            val available=roots.values.count {it.documents!=null}
            val pending=result.count {PortableStatistics.location(it.key)==null && it.kind=="L"}
            status=if(errors.isNotEmpty())"Statistics sync pending; local observations kept. ${errors.distinct().joinToString("; ")}" else if(available==0)"Choose music roots to save statistics alongside weights" else
                "Statistics saved beside weights in $available music root(s). Sync IgnorantShuffle.statistics.*.tsv with the weights. "+
                if(pending>0)"$pending unlinked listening records stay pending on this device." else ""
            return result
        }
        return records(local)
    }
    fun cleared(id: String)=roots.values.any {root ->root.ledger.records.values.any {it.kind=="X" && it.key==id}}
    fun clear() {
        roots.values.forEach {root ->
            val tombstones=PortableStatistics().apply {merge(root.ledger);clear(origin,System.currentTimeMillis())}
            tombstones.records.values.filter {it.kind=="X" && it.id !in root.ledger.records}.forEach {root.own.merge(it)}
            root.ledger.merge(root.own);persist(root)
        }
        roots.values.forEach {root ->root.documents?.let {runCatching {SharedStatisticsFile.save(it,PortableStatistics.filename(origin),root.own)}.onFailure {status="Clear markers saved locally; sync pending: ${it.message}"}}}
    }
}
