package dev.ignorantshuffle.companion

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
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
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.ZonedDateTime
import java.util.UUID

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
    private val preferenceStore = PreferenceStore(context)
    private val model = ShuffleModel(NativePlanner())
    private val journal = DiagnosticJournal(context.filesDir)
    private val notifications = RollNotifications(context) { log(it) }
    private val runId = UUID.randomUUID().toString().take(8)
    private val power = context.getSystemService(PowerManager::class.java)
    private val wake by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IgnorantShuffle:timed-playback").apply { setReferenceCounted(false) }
    }
    private val cpu = CpuLease(object : LeaseHandle {
        override fun acquire(timeout: Long) { wake.acquire(timeout) }
        override fun release() { if (wake.isHeld) wake.release() }
    }, { task, delay -> handler.postDelayed(task, delay); Unit }, { handler.removeCallbacks(it) }, { log(it) },
        { e -> log("CPU protection failed: $e"); disableAutomatic("Timed mode stopped: CPU protection unavailable") })
    private var native: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var listening = false
    private var operation: Pending? = null
    private var actions = 0L
    private var scheduled: ScheduledRoll? = null
    private var publishedSequence = -1L
    private var environmentRegistered = false
    var preferences by mutableStateOf(AppPreferences()); private set
    var rollSequence by mutableStateOf(0L); private set
    var rollReason by mutableStateOf("No roll yet"); private set
    var logError by mutableStateOf<String?>(null); private set
    var notificationStatus by mutableStateOf("Next-roll notifications are off"); private set
    var notificationsAllowed by mutableStateOf(false); private set
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
        events = journal.tail()
        runCatching { preferenceStore.load() }.onSuccess { preferences = it }
            .onFailure { log("Preference read failed; using defaults: $it") }
        log("PROCESS START; IgnorantShuffle Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); Core ${ShuffleNative.coreVersion()}; Android ${Build.VERSION.RELEASE}; ${Build.MANUFACTURER} ${Build.MODEL}")
        try {
            val loaded = store.load()
            settings = loaded.first; weights = loaded.second
            model.configure(settings, weights)
        } catch (e: Exception) { storageError = e.toString(); log("Saved settings unreadable; preserved: $e") }
    }

    fun log(text: String) {
        if (!preferences.logging) return
        val line = "${ZonedDateTime.now()} elapsed=${SystemClock.elapsedRealtime()} uptime=${SystemClock.uptimeMillis()} " +
            "run=$runId screen=${power.isInteractive} idle=${power.isDeviceIdleMode} $text"
        events = (events + line).takeLast(200)
        journal.append(line)
        logError = journal.failure
    }

    fun changePreferences(value: AppPreferences) = guarded {
        if (value.leadMs != preferences.leadMs) check(!pending) { "Wait for the pending playback command" }
        val timingChanged = value.leadMs != preferences.leadMs
        preferenceStore.save(value)
        if (!value.logging) log("Automatic logging disabled by user")
        preferences = value
        if (value.logging) log("App preferences updated: $value")
        refreshNotifications()
        if (timingChanged) { log("Automatic switch lead changed to ${value.leadMs}ms"); scheduleTimer() }
    }

    fun refreshNotifications() {
        notificationsAllowed = notifications.allowed()
        if (!connected || storageError != null) {
            notifications.clear()
            notificationStatus = if (preferences.notifyRoll) "Waiting for a connected AIMP session" else "Next-roll notifications are off"
        } else notificationStatus = notifications.render(preferences, preview, preview.track?.let(::weight) ?: 0.0,
            settings, automatic, rollSequence, playback.state)
    }

    private val environment = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { log("SYSTEM ${intent?.action}; scheduled=$scheduled") }
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
        if (!environmentRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(environment, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(environment, filter)
            environmentRegistered = true
        }
        attach(manager.getActiveSessions(component))
    }

    private fun attach(list: List<MediaController>) {
        val matches = list.filter { it.packageName == "com.aimp.player" }
        val c = matches.firstOrNull { it.sessionToken == native?.sessionToken } ?: matches.firstOrNull()
        if (c == null) { detach("No active AIMP session. Start music in AIMP."); return }
        if (c.sessionToken == native?.sessionToken) { read(refreshQueue = true); return }
        detach("Switching AIMP session")
        val cb = object : MediaController.Callback() {
            private fun active() = native?.sessionToken == c.sessionToken
            override fun onMetadataChanged(metadata: MediaMetadata?) { if (active()) guarded { read() } }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (active()) guarded { log("Playback callback state=${state?.state}, position=${state?.position}"); read() }
            }
            override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) { if (active()) guarded { read(refreshQueue = true) } }
            override fun onSessionDestroyed() { if (active()) { detach("AIMP session destroyed"); refresh() } }
        }
        c.registerCallback(cb, handler)
        native = c; callback = cb; connected = true
        status = "Connected to AIMP; ${matches.size} session(s)"
        log(status)
        val aimpVersion = runCatching { context.packageManager.getPackageInfo("com.aimp.player", 0).versionName }.getOrNull()
        log("AIMP version=$aimpVersion; powerSave=${power.isPowerSaveMode}; batteryExempt=${power.isIgnoringBatteryOptimizations(context.packageName)}")
        read(refreshQueue = true)
    }

    private fun detach(reason: String) {
        disableAutomatic("Timed mode stopped: $reason")
        if (pending) finish("INCONCLUSIVE: $reason")
        native?.let { c -> callback?.let { cb -> runCatching { c.unregisterCallback(cb) } } }
        native = null; callback = null; connected = false; actions = 0
        model.disconnect(); tracks = emptyList(); playback = Playback(); preview = Choice(null); canPrevious = false
        status = reason
        notifications.clear()
        notificationStatus = if (preferences.notifyRoll) "Waiting for a connected AIMP session" else "Next-roll notifications are off"
        log(reason)
    }

    fun stop(reason: String) {
        if (environmentRegistered) runCatching { context.unregisterReceiver(environment) }
        environmentRegistered = false
        if (listening) runCatching { manager.removeOnActiveSessionsChangedListener(sessions) }
        listening = false
        detach(reason)
    }

    private fun uri(value: String?): String? = value?.takeIf { it.isNotBlank() && !Uri.parse(it).scheme.isNullOrBlank() }

    private fun read(rearm: Boolean = true, refreshQueue: Boolean = false) {
        val c = native ?: return
        val state = c.playbackState
        val metadata = c.metadata
        val queue = if (refreshQueue || tracks.isEmpty()) c.queue?.map {
            val d = it.description
            Track(it.queueId, d.mediaId?.takeIf(String::isNotBlank), uri(d.mediaUri?.toString()),
                d.title?.toString().orEmpty(), d.subtitle?.toString().orEmpty())
        }.orEmpty() else tracks
        if (queue != tracks) {
            log("CATALOG changed: ${tracks.size} -> ${queue.size} entries; targets=${queue.take(20)}")
            tracks = queue; model.catalog(queue)
        }
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
            log("VERIFY ${op.label}; expected=${op.target}; actual=${next.track?.key}; state=${next.state}; position=${next.position}; matched=$verified")
            if (verified) finish("OBSERVED: ${op.label} matched subsequent playback")
        }
        updatePreview("Playback/catalog observation")
        if (rearm) scheduleTimer()
    }

    private fun updatePreview(reason: String = "Settings changed") {
        if (storageError != null) return
        preview = model.next()
        canPrevious = model.previous() != null
        if (publishedSequence != model.choiceSequence) {
            publishedSequence = model.choiceSequence; rollSequence = publishedSequence; rollReason = reason
            log("ROLL #$rollSequence reason=$reason; source=${playback.track}; choice=$preview; settings=$settings; targetWeight=${preview.track?.let(::weight)}")
        }
        refreshNotifications()
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
    fun reroll() = guarded { if (!pending) { model.next(true); updatePreview("User reroll"); scheduleTimer() } }
    fun smartNext() = guarded { if (!pending) playChoice(model.next()) }
    fun smartPrevious() = guarded { if (!pending) model.previous()?.let(::playChoice) }
    fun play(track: Track) = guarded { if (!pending) playChoice(Choice(track)) }

    private fun playChoice(choice: Choice, planned: ScheduledRoll? = null) {
        check(storageError == null) { "Storage error; playback selection disabled" }
        val target = choice.track ?: run { result = "No eligible tracks / finite pass complete"; return }
        // Manual requests refresh the queue. Timed requests already performed a
        // lightweight source read; do not refetch/hash the whole queue twice here.
        if (planned == null) read(refreshQueue = true)
        if (pending) return
        if (planned != null && !HandoffPolicy.valid(planned, timing(), model.choiceSequence, SystemClock.elapsedRealtime())) {
            log("Timed choice cancelled: source/timing changed during revalidation"); scheduleTimer(); return
        }
        val fresh = tracks.firstOrNull { it.key == target.key } ?: error("Queue changed; choose again")
        val c = native ?: error("No AIMP session")
        check(granted()) { "Notification access was revoked" }
        val transport = c.transportControls
        log("DISPATCH roll=${planned?.sequence ?: rollSequence}; source=${playback.track}; target=$fresh; choice=$choice")
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
        if (expectedSource != null && !EndTiming.due(timing(), expectedSource, SystemClock.elapsedRealtime(), preferences.leadMs)) {
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
        handler.removeCallbacks(endTimer); scheduled = null
        operation = Pending(label, key, playback); pending = true
        result = "Waiting for AIMP: $label"
        log("COMMAND $label; target=$key")
        handler.postDelayed(timeout, 8000)
        sustainCpu()
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
        log("USER ENABLED timed shuffle; lead=${preferences.leadMs}ms; CPU protection while playing; no queue insertion")
        read()
        refreshNotifications()
    }

    private fun disableAutomatic(reason: String) {
        handler.removeCallbacks(endTimer); scheduled = null
        if (automatic) log(reason)
        automatic = false; automaticStatus = reason
        cpu.update(false)
        refreshNotifications()
    }

    private fun timing() = TimingSnapshot(playback.track?.key, playback.state, playback.position,
        playback.duration, playback.speed, playback.updatedAt)

    private fun scheduleTimer() {
        handler.removeCallbacks(endTimer); scheduled = null
        sustainCpu()
        if (!automatic || pending) return
        val delay = EndTiming.delayMillis(timing(), SystemClock.elapsedRealtime(), preferences.leadMs)
        if (delay == null) {
            automaticStatus = "Waiting for PLAYING state with a known track, position, speed and duration"
            return
        }
        scheduled = ScheduledRoll(requireNotNull(playback.track).key, preview, model.choiceSequence,
            SystemClock.elapsedRealtime() + delay, SystemClock.uptimeMillis() + delay, preferences.leadMs)
        automaticStatus = "Timed switch in approximately ${delay / 1000}s; lead ${preferences.leadMs}ms. AIMP/Android timing can differ."
        log("TIMER armed roll=$rollSequence target=${preview.track}; delay=$delay; scheduled=$scheduled; sample=${timing()}")
        handler.postDelayed(endTimer, delay)
    }

    private fun sustainCpu() {
        // AIMP can briefly report PAUSED/BUFFERING during the requested handoff.
        // Keep the bounded command-confirmation window awake through that state.
        cpu.update(CpuProtection.needed(automatic, pending, timing(), SystemClock.elapsedRealtime(), preferences.leadMs))
    }

    private val endTimer = Runnable {
        val planned = scheduled
        scheduled = null
        guarded {
            if (automatic && !pending && planned != null) {
                log("TIMER fired roll=${planned.sequence}; elapsedLate=${SystemClock.elapsedRealtime() - planned.elapsedDeadline}ms; uptimeLate=${SystemClock.uptimeMillis() - planned.uptimeDeadline}ms")
                read(rearm = false)
                if (HandoffPolicy.valid(planned, timing(), model.choiceSequence, SystemClock.elapsedRealtime())) {
                    log("Timed deadline reached for ${planned.source}; captured roll=${planned.sequence}")
                    val choice = planned.choice
                    if (choice.track == null) {
                        disableAutomatic("No eligible tracks / finite pass complete; requesting Pause at estimated end")
                        basic(false, planned.source)
                    } else playChoice(choice, planned)
                } else {
                    log("HANDOFF CANCELLED: planned=$planned; actual=$playback; currentRoll=${model.choiceSequence}. AIMP/manual playback left unchanged.")
                    scheduleTimer()
                }
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
    fun savedEvents(): List<String> = journal.contents().lineSequence().filter(String::isNotBlank).toList()
    fun report(full: Boolean = false): String = buildString {
        appendLine("Diagnostic export: ${ZonedDateTime.now()}")
        appendLine("IgnorantShuffle Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); Core ${ShuffleNative.coreVersion()}\n$status\n$settings\n$playback\nPreview: $preview\nResult: $result")
        appendLine("Preferences: $preferences; log error: ${journal.failure}; roll=$rollSequence reason=$rollReason")
        appendLine("Scheduled: $scheduled; screen=${power.isInteractive}; idle=${power.isDeviceIdleMode}")
        appendLine("CPU held=${wake.isHeld}; powerSave=${power.isPowerSaveMode}; batteryExempt=${power.isIgnoringBatteryOptimizations(context.packageName)}")
        (if (full) tracks else tracks.take(20)).forEach { appendLine("TRACK $it key=${it.key} weight=${weight(it)}") }
        if (!full && tracks.size > 20) appendLine("Queue truncated in summary; export full log for every track")
        appendLine("Queue: ${tracks.size}; explicit weights: ${weights.size}; current-pass starts: ${model.played.size}")
        appendLine("Timed mode: $automatic; $automaticStatus")
        appendLine("Immediate companion controls; AIMP's own next-track selection is independent.")
        if (full) appendLine(journal.contents())
        else events.takeLast(100).forEach { appendLine(it) }
    }
}
