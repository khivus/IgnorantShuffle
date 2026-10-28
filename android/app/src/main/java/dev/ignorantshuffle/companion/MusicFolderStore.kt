package dev.ignorantshuffle.companion

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only system-picker tree URIs are used. No broad storage permission or invented paths. */
class MusicFolderStore(private val context: Context) {
    private val resolver = context.contentResolver
    private val cache = AtomicFile(File(context.filesDir, "music-folder.json"))
    private data class Node(val uri: Uri, val id: String, val name: String, val mime: String)
    private fun root(tree: Uri) = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private fun children(tree: Uri, parent: Uri): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        return requireNotNull(resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE), null, null, null)) { "Music folder cannot be listed" }.use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                add(Node(DocumentsContract.buildDocumentUriUsingTree(tree, id), id, cursor.getString(1), cursor.getString(2)))
            } }
        }
    }
    fun label(tree: Uri): String = requireNotNull(resolver.query(root(tree), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)).use {
        check(it.moveToFirst()) { "Music folder is unavailable" }; it.getString(0)
    }
    fun load(): Pair<FolderConfig, List<LibraryFile>> {
        if (!cache.baseFile.exists() && !File(cache.baseFile.path + ".bak").exists()) return FolderConfig() to emptyList()
        val json = JSONObject(cache.openRead().bufferedReader().use { it.readText() })
        require(json.getInt("version") == 1) { "Unsupported music-folder settings version" }
        val links = json.getJSONObject("links")
        val config = FolderConfig(json.optString("root").takeIf { it.isNotBlank() }, json.getString("label"),
            json.optBoolean("enabled", true), links.keys().asSequence().associateWith { links.getString(it) })
        val files = json.getJSONArray("files")
        return config to (0 until files.length()).map { i -> files.getJSONObject(i).let {
            val path = it.getString("path"); require(PortableWeights.relative(path)) { "Invalid cached relative path" }
            LibraryFile(path, it.getString("uri"), it.getString("title"), it.getString("artist"))
        } }
    }
    fun save(config: FolderConfig, files: List<LibraryFile>) {
        val json = JSONObject().put("version", 1).put("root", config.root.orEmpty()).put("label", config.label)
            .put("enabled", config.enabled).put("links", JSONObject(config.links)).put("files", JSONArray().apply {
                files.forEach { put(JSONObject().put("path", it.path).put("uri", it.uri).put("title", it.title).put("artist", it.artist)) }
            })
        val stream = cache.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); cache.finishWrite(stream) }
        catch (e: Exception) { cache.failWrite(stream); throw e }
    }
    fun scan(tree: Uri, progress: (Int) -> Unit): List<LibraryFile> {
        val files = mutableListOf<LibraryFile>()
        val visited = hashSetOf<String>()
        val extensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ape", "alac", "wv", "aiff", "aif", "dsf", "dff", "mka")
        var entries = 0
        fun visit(folder: Uri, prefix: String, depth: Int) {
            check(depth <= 64) { "Music-folder tree is too deep" }
            if (!visited.add(DocumentsContract.getDocumentId(folder))) return
            children(tree, folder).forEach { node ->
                check(++entries <= 100_000) { "Music-folder scan exceeds 100000 entries" }
                val path = prefix + node.name
                require(PortableWeights.relative(path)) { "Unsupported file/folder name: $path" }
                if (node.mime == Document.MIME_TYPE_DIR) visit(node.uri, "$path/", depth + 1)
                else if (node.mime.startsWith("audio/") || node.name.substringAfterLast('.', "").lowercase() in extensions) {
                    var title = node.name.substringBeforeLast('.', node.name)
                    var artist = ""
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(context, node.uri)
                        title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: title
                        artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                    } catch (_: Exception) { /* Keep the real path for manual linking of unreadable tags. */ }
                    finally { runCatching { metadata.release() } }
                    files.add(LibraryFile(path, node.uri.toString(), title, artist))
                    if (files.size % 20 == 0) progress(files.size)
                }
            }
        }
        visit(root(tree), "", 0)
        check(files.map { it.path }.distinct().size == files.size) { "Provider exposes duplicate relative paths" }
        progress(files.size)
        return files.sortedBy { it.path }
    }
    fun documents(tree: Uri): WeightDocuments = object : WeightDocuments {
        override fun find(name: String): String? {
            val found = children(tree, root(tree)).filter { it.name == name }
            check(found.size <= 1 && found.none { it.mime == Document.MIME_TYPE_DIR }) { "Ambiguous shared file: $name" }
            return found.singleOrNull()?.uri?.toString()
        }
        override fun read(id: String): String = requireNotNull(resolver.openInputStream(Uri.parse(id))).use {
            val bytes = it.readNBytesCompat(8_000_000)
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        override fun create(name: String): String = requireNotNull(DocumentsContract.createDocument(resolver, root(tree), "text/tab-separated-values", name)) {
            "Cannot create shared weights; choose a writable on-device folder"
        }.toString()
        override fun write(id: String, text: String) {
            require(text.toByteArray(Charsets.UTF_8).size <= 8_000_000) { "Shared weights exceed 8 MB" }
            requireNotNull(resolver.openOutputStream(Uri.parse(id), "wt")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        override fun rename(id: String, name: String): String = requireNotNull(DocumentsContract.renameDocument(resolver, Uri.parse(id), name)) {
            "Folder provider cannot safely rename weights; choose a local music folder"
        }.toString()
        override fun delete(id: String) { check(DocumentsContract.deleteDocument(resolver, Uri.parse(id))) { "Cannot remove shared temporary/backup file" } }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer); if (count < 0) break
        require(output.size() + count <= limit) { "Shared weights exceed 8 MB" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
