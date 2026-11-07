package dev.ignorantshuffle.companion

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/** Only indexes and file links are cached privately. Track weights live in each music root. */
class FolderWeightsController(context: Context, private val event: (String) -> Unit, private val changed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store = MusicFolderStore(context)
    private var matcher = LibraryMatcher(emptyList())
    var config by mutableStateOf(FolderConfig()); private set
    var files by mutableStateOf(emptyList<LibraryFile>()); private set
    var values by mutableStateOf(emptyMap<String, Map<String, Double>>()); private set
    var busy by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var status by mutableStateOf("Loading music folders"); private set
    val active get() = config.roots.isNotEmpty()
    val blocked get() = busy || error != null
    private data class Snapshot(val config: FolderConfig, val files: List<LibraryFile>,
        val values: Map<String, Map<String, Double>>, val note: String)

    init {
        worker.execute {
            try {
                val saved = store.load()
                handler.post { config = saved.first; files = saved.second; matcher = LibraryMatcher(files) }
                complete(Snapshot(saved.first, saved.second, readRoots(saved.first), "Music folders loaded"))
            } catch (e: Exception) { failed("Load music folders", e) }
        }
    }
    private fun readRoots(config: FolderConfig) = config.roots.associate { root ->
        root.uri to SharedWeightFile.read(store.documents(Uri.parse(root.uri)))
    }
    fun match(track: Track) = matcher.match(track, config.links)
    fun pickerFailed(exception: Throwable) { status = "Music-folder selection failed: ${exception.message}"; event(status); changed() }
    fun effective(tracks: List<Track>): Map<String, Double> = buildMap {
        tracks.forEach { track -> val match = match(track)
            match.path?.let { this@FolderWeightsController.values[match.root]?.get(it) }?.let { put(track.key, it) }
        }
    }
    private fun complete(snapshot: Snapshot) { handler.post {
        config = snapshot.config; files = snapshot.files; values = snapshot.values
        matcher = LibraryMatcher(files); busy = false; error = null
        status = "${snapshot.note}; ${files.size} files; ${values.values.sumOf { it.size }} saved weights"
        event(status); changed()
    } }
    private fun failed(label: String, exception: Exception) { handler.post {
        busy = false; error = "$label failed: ${exception.message}"
        status = "Weights preserved. Restore folder access or fix the weights file, then reload."
        event("FOLDER ERROR: $exception"); changed()
    } }
    private fun launch(label: String, action: () -> Snapshot) {
        if (busy) { event("Music-folder operation already in progress; skipped: $label"); return }
        busy = true; error = null; status = label; changed()
        worker.execute { try { complete(action()) } catch (e: Exception) { failed(label, e) } }
    }
    fun select(uri: Uri) = launch("Indexing music folder") {
        val id = DocumentsContract.getTreeDocumentId(uri)
        config.roots.filter { it.uri != uri.toString() }.forEach { root ->
            val other = Uri.parse(root.uri)
            val otherId = DocumentsContract.getTreeDocumentId(other)
            check(uri.authority != other.authority || (id != otherId && !id.startsWith("$otherId/") && !otherId.startsWith("$id/"))) {
                "Choose separate music roots; this folder overlaps an existing root"
            }
        }
        val indexed = store.scan(uri) { count -> handler.post { status = "Indexing music folder: $count audio files" } }
        val root = MusicRoot(uri.toString(), store.label(uri))
        val next = config.copy(roots = config.roots.filter { it.uri != root.uri } + root, transferRoot = root.uri)
        val allFiles = files.filter { it.root != root.uri } + indexed
        val weights = SharedWeightFile.edit(store.documents(uri)) { }
        store.save(next, allFiles)
        Snapshot(next, allFiles, values + (root.uri to weights), "Music folder added")
    }
    fun reload(rescan: Boolean = false) {
        // onResume can run before the initial load posts its result to the main
        // thread. That load already reads the files; do not start a second one.
        if (busy || config.roots.isEmpty()) return
        launch(if (rescan) "Reindexing music folders" else "Reloading weights") {
            val indexed = if (rescan) config.roots.flatMap { store.scan(Uri.parse(it.uri)) { count ->
                handler.post { status = "Indexing ${it.label}: $count audio files" }
            } } else files
            val weights = readRoots(config)
            if (rescan) store.save(config, indexed)
            Snapshot(config, indexed, weights, if (rescan) "Music folders reindexed" else "Weights reloaded")
        }
    }
    fun selectTransferRoot(root: String) = launch("Selecting import/export folder") {
        check(config.roots.any { it.uri == root })
        val next = config.copy(transferRoot = root); store.save(next, files)
        Snapshot(next, files, values, "Import/export folder selected")
    }
    fun removeRoot(root: String) = launch("Removing music folder") {
        val next = config.copy(roots = config.roots.filter { it.uri != root })
        val remaining = files.filter { it.root != root }
        store.save(next, remaining)
        Snapshot(next, remaining, values - root, "Folder removed; its weights file was kept")
    }
    fun link(track: Track, id: String?) = launch("Saving file link") {
        check(id == null || files.any { it.id == id }) { "Choose an indexed music file" }
        val links = config.links.toMutableMap().apply { if (id == null) remove(track.key) else put(track.key, id) }
        val next = config.copy(links = links.toMap()); store.save(next, files)
        Snapshot(next, files, values, "File link saved")
    }
    fun edit(track: Track, value: Double?) {
        val match = match(track)
        val path = match.path ?: error("Link this playlist entry to a music file first")
        launch("Saving weight: $path") {
            val updated = SharedWeightFile.edit(store.documents(Uri.parse(match.root))) { if (value == null) it.remove(path) else it[path] = value }
            Snapshot(config, files, values + (match.root to updated), "Weight saved in music root")
        }
    }
    fun import(text: String) {
        PortableWeights.parse(text)
        val root = config.root ?: error("Choose a music root for import")
        launch("Replacing music-root weights file") {
            val updated = SharedWeightFile.replace(store.documents(Uri.parse(root)), text)
            Snapshot(config, files, values + (root to updated), "Weights file replaced")
        }
    }
    fun export(copy: (String) -> Unit) {
        val root = config.root ?: error("Choose a music root for export")
        launch("Copying music-root weights file") {
            copy(SharedWeightFile.text(store.documents(Uri.parse(root))))
            Snapshot(config, files, values, "Weights file exported")
        }
    }
}
