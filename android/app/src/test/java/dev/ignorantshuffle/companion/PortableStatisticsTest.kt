package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PortableStatisticsTest {
    private fun play(id: String="android",ms: Long=30000)=PortableStat("$id/l/2026-11-10",kind="L",key="Artist/Track.flac",title="Track, \"quoted\"",artist="Artist",session=id,day="2026-11-10",started=1000,updated=ms+1000,ms=ms,plays=1)
    private class Documents: WeightDocuments {
        val files=linkedMapOf(PortableWeights.FILENAME to "# weights kept unchanged\n5\tArtist/Track.flac\n")
        var failWrites=false
        override fun names()=files.keys.toList()
        override fun find(name: String)=name.takeIf {it in files}
        override fun read(id: String)=requireNotNull(files[id])
        override fun create(name: String): String {check(name !in files);files[name]="";return name}
        override fun write(id: String,text: String) {check(!failWrites);files[id]=text}
        override fun rename(id: String,name: String): String {check(name !in files);files[name]=requireNotNull(files.remove(id));return name}
        override fun delete(id: String) {check(files.remove(id)!=null)}
    }
    @Test fun cppGoldenFileAndUnicodeRoundTrip() {
        val text=requireNotNull(javaClass.classLoader?.getResource("portable-statistics.tsv")).readText(Charsets.UTF_8)
        val ledger=PortableStatistics.parse(text)
        assertEquals(2,ledger.active().size);assertEquals(70000L,ledger.active().sumOf {it.ms})
        ledger.put(play("emoji").copy(title="Музыка 🎵\n\t",key="Музыка/🎵.flac"))
        assertEquals(ledger.records,PortableStatistics.parse(ledger.text()).records)
    }
    @Test fun revisionsMergingConflictsAndClearAreDeterministic() {
        val a=PortableStatistics();val b=PortableStatistics();a.put(play());a.put(play(ms=40000));b.put(play("windows"))
        a.merge(b);a.merge(b);assertEquals(2,a.active().sumOf {it.plays}.toInt())
        b.merge(a);assertEquals(a.records,b.records)
        assertThrows(IllegalArgumentException::class.java) {a.merge(play().copy(revision=2,ms=50000))}
        a.clear("android",50000);b.put(play(ms=60000));a.merge(b);assertTrue(a.active().isEmpty())
        a.put(play("offline-new"));assertEquals(1,a.active().size)
        assertThrows(IllegalArgumentException::class.java) {a.put(play("bad").copy(key="../outside"))}
    }
    @Test fun twoDeviceSidecarsCombineWithoutOverwritingWeightsOrDoubleCountingAndClearPropagates() {
        val docs=Documents();val weights=docs.files[PortableWeights.FILENAME]
        val dir=Files.createTempDirectory("statistics-sync-test").toFile()
        try {
            val a=SharedStatisticsStore(java.io.File(dir,"a"),"android")
            val b=SharedStatisticsStore(java.io.File(dir,"b"),"windows")
            val resolve: (String,String,String)->FileMatch={_,_,_->FileMatch("Artist/Track.flac","Fixture","music-root")}
            a.configure(listOf(StatisticsRoot("music-root",docs)),resolve);b.configure(listOf(StatisticsRoot("music-root",docs)),resolve)
            a.sync(listOf(play()),true)
            val merged=b.sync(listOf(play("windows",40000)),true)
            assertEquals(70000L,merged.sumOf {it.ms});assertEquals(2,merged.sumOf {it.plays}.toInt())
            assertEquals(2,a.sync(listOf(play()),true).size);assertEquals(weights,docs.files[PortableWeights.FILENAME])
            assertEquals(2,docs.files.keys.count(PortableStatistics::shard))
            a.clear();assertTrue(b.sync(listOf(play("windows",40000)),true).isEmpty())
            assertEquals(1,b.sync(listOf(play("windows",40000),play("offline-new")),true).size)
        } finally {dir.deleteRecursively()}
    }
    @Test fun failedSharedWritePreservesExistingFilesAndRetriesPendingObservations() {
        val docs=Documents();val dir=Files.createTempDirectory("statistics-pending-test").toFile()
        try {
            val store=SharedStatisticsStore(dir,"android")
            store.configure(listOf(StatisticsRoot("root",docs))) {_,_,_->FileMatch("Artist/Track.flac","Fixture","root")}
            store.sync(listOf(play()),true)
            val original=docs.files[PortableStatistics.filename("android")]
            docs.failWrites=true;store.sync(listOf(play(ms=40000)),true)
            assertEquals(original,docs.files[PortableStatistics.filename("android")]);assertTrue(store.status.contains("pending"))
            docs.failWrites=false;store.sync(listOf(play(ms=40000)),true)
            assertEquals(40000L,PortableStatistics.parse(requireNotNull(docs.files[PortableStatistics.filename("android")])).active().single().ms)
        } finally {dir.deleteRecursively()}
    }
    @Test fun unlinkedLegacyHistoryStaysPendingAndMigratesOnceWhenLinked() {
        val docs=Documents();val dir=Files.createTempDirectory("statistics-migration-test").toFile()
        try {
            val store=SharedStatisticsStore(dir,"android")
            assertEquals(1,store.sync(listOf(play()),true).size)
            store.configure(listOf(StatisticsRoot("root",docs))) {_,_,_->FileMatch("Artist/Track.flac","Linked","root")}
            repeat(3) {assertEquals(1,store.sync(listOf(play()),true).size)}
            assertEquals(1,PortableStatistics.parse(requireNotNull(docs.files[PortableStatistics.filename("android")])).active().size)
            assertEquals("root" to "Artist/Track.flac",PortableStatistics.location(store.records(listOf(play())).single().key))
        } finally {dir.deleteRecursively()}
    }
}
