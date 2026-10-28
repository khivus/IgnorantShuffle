package dev.ignorantshuffle.companion

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Windows-compatible UTF-8 TSV. Preserve unknown/legacy keys when editing. */
object PortableWeights {
    const val FILENAME = "IgnorantShuffle.weights.tsv"
    private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    fun parse(text: String): Map<String, Double> = linkedMapOf<String, Double>().apply {
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            val line = raw.removeSuffix("\r")
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            val parts = line.split('\t', limit = 2)
            require(parts.size == 2 && decimal.matches(parts[0].trim())) { "Line ${index + 1}: expected weight<TAB>track" }
            val value = parts[0].trim().toDoubleOrNull()
            require(value != null && value.isFinite() && value >= 0) { "Line ${index + 1}: invalid weight" }
            require(parts[1].isNotEmpty() && parts[1].none { it == '\t' || it == '\r' || it == '\n' || it == '\u0000' }) {
                "Line ${index + 1}: invalid track key"
            }
            put(parts[1], value)
        }
    }
    fun write(values: Map<String, Double>): String = buildString {
        appendLine("# IgnorantShuffle weights v1: weight<TAB>track (UTF-8)")
        values.toSortedMap().forEach { (path, value) -> appendLine("$value\t$path") }
    }.also { require(parse(it) == values) { "Invalid portable weights" } }

    fun relative(path: String): Boolean = path.isNotEmpty() && !path.startsWith('/') &&
        path.none { it == '\\' || it == ':' || it == '\t' || it == '\r' || it == '\n' || it == '\u0000' } &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}

data class LibraryFile(val path: String, val uri: String, val title: String, val artist: String)
data class FileMatch(val path: String?, val description: String)
data class FolderConfig(val root: String? = null, val label: String = "No music folder selected",
    val enabled: Boolean = true, val links: Map<String, String> = emptyMap())

class LibraryMatcher(files: List<LibraryFile>) {
    private val paths = files.associateBy { it.path }
    private val uris = files.groupBy { it.uri }
    private fun normalized(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun metadata(title: String, artist: String) = normalized(title) to normalized(artist)
    private val tags = files.filter { it.title.isNotBlank() }.groupBy { metadata(it.title, it.artist) }
    fun match(track: Track, links: Map<String, String>): FileMatch {
        links[track.key]?.let {
            return if (it in paths) FileMatch(it, "Manually linked") else FileMatch(null, "Linked file is missing; link again")
        }
        val direct = track.uri?.let { uris[it] }
        if (direct?.size == 1) return FileMatch(direct.single().path, "Media URI")
        if (track.title.isBlank()) return FileMatch(null, "Missing track title; link a file")
        val matches = tags[metadata(track.title, track.artist)].orEmpty()
        return when (matches.size) {
            1 -> FileMatch(matches.single().path, "Unique title + artist match")
            0 -> FileMatch(null, "No matching file; link a file")
            else -> FileMatch(null, "${matches.size} files share these tags; link the correct file")
        }
    }
}

/** Provider operations are isolated so replacement/rollback can be tested on the JVM. */
interface WeightDocuments {
    fun find(name: String): String?
    fun read(id: String): String
    fun create(name: String): String
    fun write(id: String, text: String)
    fun rename(id: String, name: String): String
    fun delete(id: String)
}

object SharedWeightFile {
    private const val BACKUP = "${PortableWeights.FILENAME}.bak"
    fun read(documents: WeightDocuments): Map<String, Double> {
        val id = documents.find(PortableWeights.FILENAME)
        check(id != null || documents.find(BACKUP) == null) { "Shared file is missing but its .bak exists; restore the backup before reloading" }
        return id?.let { PortableWeights.parse(documents.read(it)) }.orEmpty()
    }
    fun edit(documents: WeightDocuments, transform: (MutableMap<String, Double>) -> Unit): Map<String, Double> {
        val original = documents.find(PortableWeights.FILENAME)
        check(original != null || documents.find(BACKUP) == null) { "Restore the shared .bak file before editing" }
        val before = original?.let(documents::read)
        val values = before?.let(PortableWeights::parse).orEmpty().toMutableMap()
        val unchanged = values.toMap()
        transform(values)
        if (original != null && values == unchanged) return values.toMap()
        val text = PortableWeights.write(values)
        var temporary: String? = documents.create("${PortableWeights.FILENAME}.${UUID.randomUUID()}.tmp")
        var backup: String? = null
        var promoted: String? = null
        try {
            documents.write(requireNotNull(temporary), text)
            check(documents.read(requireNotNull(temporary)) == text) { "Shared temporary-file verification failed" }
            check(documents.find(PortableWeights.FILENAME) == original && (original == null || documents.read(original) == before)) {
                "Shared weights changed during editing; reload and try again"
            }
            if (original != null) {
                documents.find(BACKUP)?.let(documents::delete)
                backup = documents.rename(original, BACKUP)
            }
            promoted = documents.rename(requireNotNull(temporary), PortableWeights.FILENAME)
            temporary = null
            check(documents.find(PortableWeights.FILENAME) == promoted && documents.read(requireNotNull(promoted)) == text) {
                "Shared replacement verification failed"
            }
            return values.toMap()
        } catch (e: Exception) {
            // Never truncate the original. If rollback also fails, keep its backup.
            promoted?.let { runCatching { documents.delete(it) } }
            backup?.let { id -> runCatching {
                check(documents.find(PortableWeights.FILENAME) == null)
                documents.rename(id, PortableWeights.FILENAME)
            } }
            throw IllegalStateException("Cannot save shared weights: ${e.message}. Original or .bak retained; reload before retrying.", e)
        } finally {
            temporary?.let { runCatching { documents.delete(it) } }
        }
    }
}
