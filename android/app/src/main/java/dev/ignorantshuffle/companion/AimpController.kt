package dev.ignorantshuffle.companion

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.ZonedDateTime

class ShuffleApplication : Application() { val controller by lazy { AimpController(this) } }
class SessionAccessService : NotificationListenerService() {
    private val controller get() = (application as ShuffleApplication).controller
    override fun onListenerConnected() { super.onListenerConnected(); controller.log("Notification listener connected"); controller.refresh() }
    override fun onListenerDisconnected() { controller.stop("Notification listener disconnected"); super.onListenerDisconnected() }
    override fun onDestroy() { controller.stop("Notification listener destroyed"); super.onDestroy() }
}

private data class Pending(val label: String, val target: String?, val before: Playback)

class AimpController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, SessionAccessService::class.java)
    private val store = SettingsStore(context)
    private val model = ShuffleModel(NativePlanner())
    private val logFile = File(context.filesDir, "events.log")
    private var native: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var listening = false
    private var operation: Pending? = null
    private var actions = 0L
    private var armedKey: String? = null
    var automatic by mutableStateOf(false); private set
    var automaticStatus by mutableStateOf("Timed automatic mode is off"); private set
    var status by mutableStateOf("Grant notification access, then start music in AIMP"); private set
    var permission by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var playback by mutableStateOf(Playback()); private set
    var tracks by mutableStateOf(emptyList<Track>()); private set
    var settings by mutableStateOf(Settings()); private set
    var weights by mutableStateOf(emptyMap<String, Double>()); private set
    var preview by mutableStateOf(Choice(null)); private set
    var canPrevious by mutableStateOf(false); private set
    var pending by mutableStateOf(false); private set
    var result by mutableStateOf("Ready"); private set
    var storageError by mutableStateOf<String?>(null); private set
    var events by mutableStateOf(emptyList<String>()); private set

    init {
        log("PROCESS START; IgnorantShuffle Android 0.1.0; Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}")
        try {
            val loaded = store.load()
            settings = loaded.first; weights = loaded.second
            model.configure(settings, weights)
        } catch (e: Exception) { storageError = e.toString(); log("Saved settings unreadable; preserved: $e") }
    }

    fun log(text: String) {
        val line = "${ZonedDateTime.now()} +${SystemClock.elapsedRealtime()}ms $text"
        events = (events + line).takeLast(200)
        try {
            if (logFile.length() > 512_000) {
                val previous = File(context.filesDir, "events.previous.log")
                if (previous.exists() && !previous.delete()) error("Cannot rotate previous log")
                if (!logFile.renameTo(previous)) error("Cannot rotate log")
            }
            logFile.appendText(line + "\n", Charsets.UTF_8)
        } catch (_: Exception) { /* In-memory diagnostics remain available. */ }
    }

    private fun granted(): Boolean = if (Build.VERSION.SDK_INT >= 27)
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
    else AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> guarded { attach(list.orEmpty()) } }

    fun refresh() = guarded {
        permission = granted()
        if (!permission) { stop("Notification access is not granted"); return@guarded }
        if (!listening) { manager.addOnActiveSessionsChangedListener(sessions, component, handler); listening = true }
        attach(manager.getActiveSessions(component))
    }

    private fun attach(list: List<MediaController>) {
        val matches = list.filter { it.packageName == "com.aimp.player" }
        val c = matches.firstOrNull { it.sessionToken == native?.sessionToken } ?: matches.firstOrNull()
        if (c == null) { detach("No active AIMP session. Start music in AIMP."); return }
        if (c.sessionToken == native?.sessionToken) { read(); return }
        detach("Switching AIMP session")
        val cb = object : MediaController.Callback() {
            private fun active() = native?.sessionToken == c.sessionToken
            override fun onMetadataChanged(metadata: MediaMetadata?) { if (active()) guarded { read() } }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (active()) guarded { log("Playback callback state=${state?.state}, position=${state?.position}"); read() }
            }
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) { if (active()) guarded { read() } }
            override fun onSessionDestroyed() { if (active()) { detach("AIMP session destroyed"); refresh() } }
        }
        c.registerCallback(cb, handler)
        native = c; callback = cb; connected = true
        status = "Connected to AIMP; ${matches.size} session(s)"
        log(status)
        read()
    }

    private fun detach(reason: String) {
        disableAutomatic("Timed mode stopped: $reason")
        if (pending) finish("INCONCLUSIVE: $reason")
        native?.let { c -> callback?.let { cb -> runCatching { c.unregisterCallback(cb) } } }
        native = null; callback = null; connected = false; actions = 0
        model.disconnect(); tracks = emptyList(); playback = Playback(); preview = Choice(null); canPrevious = false
        status = reason
        log(reason)
    }

    fun stop(reason: String) {
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
        listening = false
        detach(reason)
    }

    private fun uri(value: String?): String? = value?.takeIf { it.isNotBlank() && !Uri.parse(it).scheme.isNullOrBlank() }

    private fun read(rearm: Boolean = true) {
        val c = native ?: return
        val state = c.playbackState
        val metadata = c.metadata
        val queue = c.queue?.map {
            val d = it.description
            Track(it.queueId, d.mediaId?.takeIf(String::isNotBlank), uri(d.mediaUri?.toString()),
                d.title?.toString().orEmpty(), d.subtitle?.toString().orEmpty())
        }.orEmpty()
        if (queue != tracks) log("Queue changed: ${queue.size} entries; identities=${queue.map { it.key }}")
        tracks = queue
        model.catalog(queue)
        val mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val mediaUri = uri(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI))
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val current = queue.firstOrNull {
            val identity = when {
                !mediaId.isNullOrBlank() && it.mediaId != null -> it.mediaId == mediaId
                mediaUri != null && it.uri != null -> it.uri == mediaUri
                else -> it.queueId == state?.activeQueueItemId
            }
            identity && (title.isBlank() || it.title.isBlank() || it.title == title)
        }
        val next = Playback(state?.state ?: 0, title.ifBlank { "Unknown track" },
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), state?.position ?: -1,
            if (metadata?.containsKey(MediaMetadata.METADATA_KEY_DURATION) == true)
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) else -1,
            state?.playbackSpeed ?: 0f, state?.lastPositionUpdateTime ?: 0, current)
        actions = state?.actions ?: 0
        if (next.title != playback.title || next.state != playback.state || next.track?.key != playback.track?.key)
            log("Observed state=${next.state}; title=${next.title}; id=$mediaId; queueId=${current?.queueId}; position=${next.position}; actions=0x${actions.toString(16)}")
        playback = next
        if (next.state == PlaybackState.STATE_PLAYING && current != null) {
            val op = operation
            val restarted = op != null && op.target == current.key && op.before.track?.key == current.key &&
                op.before.position >= 3000 && next.position in 0..999
            model.confirmPlaying(current, restarted)
        }
        operation?.let { op ->
            val verified = PlaybackEvidence.confirmed(op.label, op.target, op.before, next)
            if (verified) finish("OBSERVED: ${op.label} matched subsequent playback")
        }
        updatePreview()
        if (rearm) scheduleTimer()
    }

    private fun updatePreview() {
        if (storageError != null) return
        preview = model.next()
        canPrevious = model.previous() != null
    }

    fun weight(track: Track) = weights[track.key] ?: settings.defaultWeight

    fun configure(value: Settings) = guarded {
        check(storageError == null) { "Saved settings could not be read; existing data is preserved" }
        store.save(value, weights)
        settings = value; model.configure(value, weights)
        updatePreview(); scheduleTimer(); log("Settings changed: $value")
    }

    fun setWeight(track: Track, text: String, inherit: Boolean = false) = guarded {
        check(!pending && storageError == null)
        val next = weights.toMutableMap()
        if (inherit) next.remove(track.key)
        else {
            val number = text.toDoubleOrNull()
            require(number != null && number.isFinite() && number >= 0) { "Enter a finite nonnegative weight" }
            next[track.key] = number
        }
        store.save(settings, next); weights = next; model.configure(settings, next); updatePreview(); scheduleTimer()
        log("Weight ${track.title}: ${next[track.key] ?: "inherited"}")
    }

    fun importWeights(text: String) = guarded {
        check(!pending && storageError == null)
        val next = weights + WeightTransfer.parse(text)
        store.save(settings, next); weights = next; model.configure(settings, next); updatePreview(); scheduleTimer()
        log("Imported weight overrides transactionally")
    }

    fun exportWeights() = WeightTransfer.write(weights)
    fun newPass() = guarded { model.newPass(); updatePreview(); scheduleTimer(); log("Started a new finite pass; current track counted") }
    fun reroll() = guarded { if (!pending) { preview = model.next(true); scheduleTimer(); log("Rerolled preview; no progress consumed") } }
    fun smartNext() = guarded { if (!pending) playChoice(model.next()) }
    fun smartPrevious() = guarded { if (!pending) model.previous()?.let(::playChoice) }
    fun play(track: Track) = guarded { if (!pending) playChoice(Choice(track)) }

    private fun playChoice(choice: Choice, expectedSource: String? = null) {
        check(storageError == null) { "Storage error; playback selection disabled" }
        val target = choice.track ?: run { result = "No eligible tracks / finite pass complete"; return }
        read() // Revalidate against fresh AIMP state immediately before sending.
        if (pending) return
        if (expectedSource != null && !EndTiming.due(timing(), expectedSource, SystemClock.elapsedRealtime())) {
            log("Timed choice cancelled: source/timing changed during revalidation"); scheduleTimer(); return
        }
        val fresh = tracks.firstOrNull { it.key == target.key } ?: error("Queue changed; choose again")
        val c = native ?: error("No AIMP session")
        check(granted()) { "Notification access was revoked" }
        val transport = c.transportControls
        model.requested(choice)
        begin("Play ${fresh.title}", fresh.key)
        when {
            fresh.mediaId != null && actions and PlaybackState.ACTION_PLAY_FROM_MEDIA_ID != 0L -> transport.playFromMediaId(fresh.mediaId, null)
            fresh.queueId >= 0 && actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM != 0L -> transport.skipToQueueItem(fresh.queueId)
            fresh.uri != null && actions and PlaybackState.ACTION_PLAY_FROM_URI != 0L -> transport.playFromUri(Uri.parse(fresh.uri), null)
            else -> error("AIMP advertises no supported selection command for this track")
        }
    }

    fun basic(play: Boolean, expectedSource: String? = null) = guarded {
        if (pending) return@guarded
        val c = native ?: error("No AIMP session")
        read(); check(granted())
        if (expectedSource != null && !EndTiming.due(timing(), expectedSource, SystemClock.elapsedRealtime())) {
            log("Timed pause cancelled: source/timing changed during revalidation"); return@guarded
        }
        check(supportsBasic(play)) { "AIMP does not advertise this command" }
        begin(if (play) "Play" else "Pause", null)
        if (play) c.transportControls.play() else c.transportControls.pause()
    }

    fun supportsBasic(play: Boolean) = actions and (if (play) PlaybackState.ACTION_PLAY else PlaybackState.ACTION_PAUSE) != 0L

    private val timeout = Runnable {
        guarded { read() }
        if (pending) finish("INCONCLUSIVE after 8 seconds; no confirming playback. No retry sent.")
    }
    private fun begin(label: String, key: String?) {
        handler.removeCallbacks(endTimer); armedKey = null
        operation = Pending(label, key, playback); pending = true
        result = "Waiting for AIMP: $label"
        log("COMMAND $label; target=$key")
        handler.postDelayed(timeout, 8000)
    }
    private fun finish(message: String) {
        handler.removeCallbacks(timeout); operation = null; pending = false; model.cancelRequest()
        result = message; log("RESULT $message")
        if (!message.startsWith("OBSERVED:")) disableAutomatic("Timed mode stopped after unconfirmed command")
    }

    fun changeAutomatic(enabled: Boolean) = guarded {
        if (!enabled) { disableAutomatic("Timed automatic mode is off"); return@guarded }
        check(connected && permission && storageError == null && !pending) { "Connect AIMP and finish the current command first" }
        automatic = true
        log("USER ENABLED experimental timed shuffle; lead=${EndTiming.LEAD_MS}ms; no wake lock or queue insertion")
        read()
    }

    private fun disableAutomatic(reason: String) {
        handler.removeCallbacks(endTimer); armedKey = null
        if (automatic) log(reason)
        automatic = false; automaticStatus = reason
    }

    private fun timing() = TimingSnapshot(playback.track?.key, playback.state, playback.position,
        playback.duration, playback.speed, playback.updatedAt)

    private fun scheduleTimer() {
        handler.removeCallbacks(endTimer); armedKey = null
        if (!automatic || pending) return
        val delay = EndTiming.delayMillis(timing(), SystemClock.elapsedRealtime())
        if (delay == null) {
            automaticStatus = "Waiting for PLAYING state with a known track, position, speed and duration"
            return
        }
        armedKey = playback.track?.key
        automaticStatus = "Timed switch in approximately ${delay / 1000}s; clips about ${EndTiming.LEAD_MS}ms. AIMP/Android timing can differ."
        handler.postDelayed(endTimer, delay)
    }

    private val endTimer = Runnable {
        val expected = armedKey
        armedKey = null
        guarded {
            if (automatic && !pending && expected != null) {
                read(rearm = false)
                if (EndTiming.due(timing(), expected, SystemClock.elapsedRealtime())) {
                    log("Timed deadline reached for $expected; fresh source identity checked")
                    val choice = model.next()
                    if (choice.track == null) {
                        disableAutomatic("No eligible tracks / finite pass complete; requesting Pause at estimated end")
                        basic(false, expected)
                    } else playChoice(choice, expected)
                } else scheduleTimer() // Paused, sought, delayed or changed source: respect AIMP's latest state.
            }
        }
    }

    private inline fun guarded(action: () -> Unit) {
        try { action() }
        catch (e: Exception) {
            if (pending) finish("EXCEPTION: ${e.message}")
            result = e.message ?: e.toString(); log("ERROR: $e")
            disableAutomatic("Timed mode stopped after an error")
            if (e is SecurityException) stop("Session access denied; check notification access")
        } catch (e: LinkageError) {
            disableAutomatic("Timed mode stopped: native library unavailable")
            result = "Native shuffle library unavailable: $e"; storageError = result; log(result)
        }
    }

    val passCount get() = model.played.size
    fun report(full: Boolean = false): String = buildString {
        appendLine("IgnorantShuffle Android 0.1.0\n$status\n$settings\n$playback\nPreview: $preview\nResult: $result")
        appendLine("Queue: ${tracks.size}; explicit weights: ${weights.size}; current-pass starts: ${model.played.size}")
        appendLine("Timed mode: $automatic; $automaticStatus")
        appendLine("Immediate companion controls; AIMP's own next-track selection is independent.")
        if (full) listOf(File(context.filesDir, "events.previous.log"), logFile).filter { it.exists() }
            .forEach { appendLine(it.readText(Charsets.UTF_8)) }
        else events.takeLast(100).forEach { appendLine(it) }
    }
}
