package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class PortableWeightsTest {
    private val track = Track(7, "opaque/aimp-id", null, "Song", "Artist")
    private val file = LibraryFile("Album/Song.flac", "content://real-file/1", "Song", "Artist")
    private fun rejects(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: Exception) { failed = true }
        assertTrue("Invalid operation must fail", failed)
    }
    @Test fun windowsAndAndroidReadTheSameFixture() {
        val text = requireNotNull(javaClass.getResourceAsStream("/portable-weights.tsv")).bufferedReader().use { it.readText() }
        val values = PortableWeights.parse(text)
        assertEquals(4, values.size)
        assertEquals(0.0, values["Album/Excluded.flac"]!!, 0.0)
        assertEquals(2.5, values["Album/Quiet.mp3"]!!, 0.0)
        assertEquals(25.125, values["Музыка/Любимая песня.flac"]!!, 0.0)
        assertEquals(100.0, values["Other/Big.ogg"]!!, 0.0)
        assertEquals(values, PortableWeights.parse(PortableWeights.write(values)))
    }
    @Test fun acceptsWindowsBomCrLfAndPreservesUnknownKeys() {
        val values = PortableWeights.parse("\uFEFF# shared\r\n0\tAlbum/A.mp3\r\n1.25\tC:\\old\\legacy.flac\r\n9\tAlbum/A.mp3\r\n")
        assertEquals(9.0, values["Album/A.mp3"]!!, 0.0)
        assertEquals(values, PortableWeights.parse(PortableWeights.write(values)))
    }
    @Test fun corruptFileCannotPartiallyApply() {
        listOf("NaN", "Infinity", "-1", "1e999", "0x1p3", "broken").forEach {
            rejects { PortableWeights.parse("0\tExcluded.mp3\n$it\tother.mp3") }
        }
        listOf("1\t", "1\ta\tb", "1\ta\u0000b", "   ").forEach { rejects { PortableWeights.parse(it) } }
    }
    @Test fun relativeKeysCannotEscapeTheSelectedTree() {
        listOf("../a.mp3", "/a.mp3", "C:/a.mp3", "a\\b.mp3", "a//b.mp3", "./a.mp3").forEach { assertFalse(PortableWeights.relative(it)) }
        assertTrue(PortableWeights.relative(file.path))
        assertTrue(PortableWeights.relative("Музыка/песня.flac"))
    }
    @Test fun uniqueTagsMatchWithoutInventingAPathFromAimpId() {
        assertEquals(file.path, LibraryMatcher(listOf(file)).match(track, emptyMap()).path)
        assertNull(LibraryMatcher(emptyList()).match(track, emptyMap()).path)
    }
    @Test fun duplicateTagsRequireAnExplicitFileLink() {
        val other = file.copy(path = "Other/Song.flac", uri = "content://real-file/2")
        val matcher = LibraryMatcher(listOf(file, other))
        assertNull(matcher.match(track, emptyMap()).path)
        assertEquals(other.path, matcher.match(track, mapOf(track.key to other.path)).path)
    }
    @Test fun missingManualLinkDoesNotSilentlySwitchToAnotherFile() {
        assertNull(LibraryMatcher(listOf(file)).match(track, mapOf(track.key to "Gone.flac")).path)
    }
    @Test fun directUriResolvesAmbiguousTagsAndPathCaseStaysExact() {
        val other = file.copy(path = "Other/song.flac", uri = "content://real-file/2")
        assertEquals(other.path, LibraryMatcher(listOf(file, other)).match(track.copy(uri = other.uri), emptyMap()).path)
    }

    private class Documents : WeightDocuments {
        data class Entry(var name: String, var text: String)
        val entries = linkedMapOf<String, Entry>()
        var next = 0
        var renameCalls = 0
        var failRenames = emptySet<Int>()
        var corruptWrite = false
        var beforeRead: ((String) -> Unit)? = null
        override fun find(name: String) = entries.entries.singleOrNull { it.value.name == name }?.key
        override fun read(id: String): String { beforeRead?.invoke(id); return entries.getValue(id).text }
        override fun create(name: String) = "id-${++next}".also { entries[it] = Entry(name, "") }
        override fun write(id: String, text: String) { entries.getValue(id).text = text + if (corruptWrite) "corruption" else "" }
        override fun rename(id: String, name: String): String {
            check(++renameCalls !in failRenames) { "Injected rename failure" }
            check(find(name) == null) { "Name already exists" }
            val entry = entries.remove(id)!!; entry.name = name
            return "id-${++next}".also { entries[it] = entry }
        }
        override fun delete(id: String) { check(entries.remove(id) != null) }
        fun initial(text: String) { write(create(PortableWeights.FILENAME), text) }
        fun canonical() = find(PortableWeights.FILENAME)?.let(::read)
    }
    @Test fun editRereadsFileAndPreservesOtherDevicesValuesIncludingZero() {
        val docs = Documents(); docs.initial("0\tExcluded.mp3\n7\tOther.mp3\n")
        val result = SharedWeightFile.edit(docs) { it["New.mp3"] = 2.5 }
        assertEquals(0.0, result["Excluded.mp3"]!!, 0.0)
        assertEquals(7.0, result["Other.mp3"]!!, 0.0)
        assertEquals(result, SharedWeightFile.read(docs))
        assertEquals(2, docs.entries.size) // canonical + original backup, no temporary files
    }
    @Test fun failedTemporaryVerificationLeavesOriginalIntact() {
        val docs = Documents(); val original = "0\tExcluded.mp3\n"; docs.initial(original); docs.corruptWrite = true
        rejects { SharedWeightFile.edit(docs) { it["New.mp3"] = 5.0 } }
        assertEquals(original, docs.canonical()); assertEquals(1, docs.entries.size)
    }
    @Test fun failedPromotionRollsBackUsingReturnedDocumentId() {
        val docs = Documents(); val original = "0\tExcluded.mp3\n"; docs.initial(original); docs.failRenames = setOf(2)
        rejects { SharedWeightFile.edit(docs) { it["New.mp3"] = 5.0 } }
        assertEquals(original, docs.canonical()); assertEquals(1, docs.entries.size)
    }
    @Test fun rollbackFailureRetainsBackupAndBlocksEmptyFallback() {
        val docs = Documents(); docs.initial("0\tExcluded.mp3\n"); docs.failRenames = setOf(2, 3)
        rejects { SharedWeightFile.edit(docs) { it["New.mp3"] = 5.0 } }
        assertNull(docs.canonical())
        assertNotNull(docs.find(PortableWeights.FILENAME + ".bak"))
        rejects { SharedWeightFile.read(docs) }
    }
    @Test fun concurrentSyncChangeIsDetectedWithoutOverwritingIt() {
        val docs = Documents(); docs.initial("0\tExcluded.mp3\n")
        var reads = 0
        docs.beforeRead = { id -> if (docs.entries[id]?.name == PortableWeights.FILENAME && ++reads == 2)
            docs.entries.getValue(id).text = "0\tExcluded.mp3\n9\tSynced.mp3\n" }
        rejects { SharedWeightFile.edit(docs) { it["New.mp3"] = 5.0 } }
        docs.beforeRead = null
        assertEquals(9.0, SharedWeightFile.read(docs)["Synced.mp3"]!!, 0.0)
        assertFalse(SharedWeightFile.read(docs).containsKey("New.mp3"))
    }
    @Test fun invalidExistingFileIsNeverReplaced() {
        val docs = Documents(); val original = "NaN\tExcluded.mp3\n"; docs.initial(original)
        rejects { SharedWeightFile.edit(docs) { it["New.mp3"] = 5.0 } }
        assertEquals(original, docs.canonical()); assertEquals(1, docs.entries.size)
    }
    @Test fun firstFolderCreatesAWindowsReadableFile() {
        val docs = Documents(); assertTrue(SharedWeightFile.edit(docs) { }.isEmpty())
        assertTrue(requireNotNull(docs.canonical()).startsWith("# IgnorantShuffle weights v1:"))
        assertTrue(SharedWeightFile.read(docs).isEmpty())
    }
}
