package dev.ignorantshuffle.companion

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/** All provider scans/reads/writes are serialized off the playback/UI thread. */
class FolderWeightsController(context: Context, private val event: (String) -> Unit, private val changed: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val store = MusicFolderStore(context)
    private var matcher = LibraryMatcher(emptyList())
    var config by mutableStateOf(FolderConfig()); private set
    var files by mutableStateOf(emptyList<LibraryFile>()); private set
    var values by mutableStateOf(emptyMap<String, Double>()); private set
    var busy by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var status by mutableStateOf("Loading music-folder settings"); private set
    val active get() = config.enabled && config.root != null
    val blocked get() = busy || (config.enabled && error != null)
    private data class Snapshot(val config: FolderConfig, val files: List<LibraryFile>, val values: Map<String, Double>, val note: String)

    init {
        worker.execute {
            try {
                val saved = store.load()
                // Retain the selected folder even when its grant/file is unavailable.
                handler.post { config = saved.first; files = saved.second; matcher = LibraryMatcher(files) }
                val weights = if (saved.first.enabled && saved.first.root != null)
                    SharedWeightFile.read(store.documents(Uri.parse(saved.first.root))) else emptyMap()
                complete(Snapshot(saved.first, saved.second, weights, "Music-folder settings loaded"))
            } catch (e: Exception) { failed("Load music folder", e) }
        }
    }
    fun match(track: Track) = matcher.match(track, config.links)
    fun pickerFailed(exception: Throwable) {
        // A failed new grant must not invalidate an already working selection.
        status = "Music-folder selection failed: ${exception.message}"
        event(status); changed()
    }
    fun effective(tracks: List<Track>, local: Map<String, Double>): Map<String, Double> = local.toMutableMap().apply {
        if (active) tracks.forEach { track -> match(track).path?.let { this@FolderWeightsController.values[it] }?.let { put(track.key, it) } }
    }
    private fun complete(snapshot: Snapshot) { handler.post {
        config = snapshot.config; files = snapshot.files; values = snapshot.values
        matcher = LibraryMatcher(files); busy = false; error = null
        status = "${snapshot.note}; ${files.size} indexed files; ${values.size} shared weights"
        event(status); changed()
    } }
    private fun failed(label: String, exception: Exception) { handler.post {
        busy = false; error = "$label failed: ${exception.message}"
        status = "Shared data preserved. Reload/reselect the folder, or turn shared weights off to use local weights."
        event("FOLDER ERROR: $exception"); changed()
    } }
    private fun launch(label: String, action: () -> Snapshot) {
        if (busy) { event("Music-folder operation already in progress"); return }
        busy = true; error = null; status = label; changed()
        worker.execute { try { complete(action()) } catch (e: Exception) { failed(label, e) } }
    }
    fun select(uri: Uri) = launch("Indexing selected music folder") {
        val indexed = store.scan(uri) { count -> handler.post { status = "Indexing music folder: $count audio files" } }
        val next = FolderConfig(uri.toString(), store.label(uri), true)
        val weights = SharedWeightFile.edit(store.documents(uri)) { }
        store.save(next, indexed)
        Snapshot(next, indexed, weights, "Music folder selected; shared weights enabled")
    }
    fun reload(rescan: Boolean = false) {
        val root = config.root ?: return
        if (!config.enabled && !rescan) return
        launch(if (rescan) "Reindexing music folder" else "Reloading shared weights") {
            val uri = Uri.parse(root)
            val indexed = if (rescan) store.scan(uri) { count -> handler.post { status = "Indexing music folder: $count audio files" } } else files
            val weights = if (config.enabled) SharedWeightFile.read(store.documents(uri)) else emptyMap()
            if (rescan) store.save(config, indexed)
            Snapshot(config, indexed, weights, if (rescan) "Music folder reindexed" else "Shared weights reloaded")
        }
    }
    fun enable(enabled: Boolean) = launch("Changing weight storage") {
        val next = config.copy(enabled = enabled)
        val weights = if (enabled && next.root != null) SharedWeightFile.read(store.documents(Uri.parse(next.root))) else emptyMap()
        store.save(next, files)
        Snapshot(next, files, weights, if (enabled) "Music-folder weights enabled; choose a folder if needed" else "Using local app weights")
    }
    fun link(track: Track, path: String?) = launch("Saving file link") {
        check(path == null || files.any { it.path == path }) { "Choose a file from the indexed music folder" }
        val links = config.links.toMutableMap().apply { if (path == null) remove(track.key) else put(track.key, path) }
        val next = config.copy(links = links.toMap())
        store.save(next, files)
        Snapshot(next, files, values, "File link saved")
    }
    fun edit(track: Track, value: Double?) {
        val path = match(track).path ?: error("Link this playlist entry to a music file first")
        update("Saving shared weight: $path") { if (value == null) it.remove(path) else it[path] = value }
    }
    private fun update(label: String, transform: (MutableMap<String, Double>) -> Unit) {
        check(active) { "Choose a music folder and enable shared weights" }
        val uri = Uri.parse(requireNotNull(config.root))
        launch(label) { Snapshot(config, files, SharedWeightFile.edit(store.documents(uri), transform), "Shared weights saved") }
    }
    fun copyMissing(tracks: List<Track>, local: Map<String, Double>) {
        val candidates = tracks.mapNotNull { t -> local[t.key]?.let { value -> match(t).path?.let { it to value } } }
        val conflicts = candidates.groupBy { it.first }.filterValues { entries -> entries.map { it.second }.distinct().size > 1 }
        check(conflicts.isEmpty()) { "Several local weights map to one file with different values; set that file's shared weight explicitly" }
        update("Copying missing local weights") { values -> candidates.forEach { (path, value) -> values.putIfAbsent(path, value) } }
    }
    fun import(text: String) {
        val imported = PortableWeights.parse(text)
        update("Importing portable shared weights") { it.putAll(imported) }
    }
}
