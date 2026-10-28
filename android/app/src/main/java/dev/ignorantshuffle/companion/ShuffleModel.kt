package dev.ignorantshuffle.companion

import java.security.MessageDigest

data class Track(val queueId: Long, val mediaId: String?, val uri: String?, val title: String, val artist: String) {
    // Conservative app-local identity. Do not persist numeric queue IDs or guess file paths.
    val key: String = run {
        val text = listOf(mediaId ?: uri ?: "unidentified", title, artist).joinToString("\u0000")
        "android:v1:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    val selectable get() = mediaId != null || uri != null || queueId >= 0
}

data class Settings(val defaultWeight: Double = 5.0, val cooldown: Int = 0, val finite: Boolean = false) {
    fun validate() {
        require(defaultWeight.isFinite() && defaultWeight >= 0) { "Default weight must be finite and nonnegative" }
        require(cooldown in 0..10000) { "Cooldown must be between 0 and 10000" }
    }
}

data class Choice(val track: Track?, val probability: Double = 0.0, val eligible: Int = 0,
    val excluded: Int = 0, val blocked: Int = 0, val cooldownFallback: Boolean = false,
    val remaining: Int = 0, val historyIndex: Int? = null)

fun interface Planner {
    fun choose(tracks: List<Track>, weights: DoubleArray, recent: List<String>, played: List<String>,
        settings: Settings, avoid: String): Choice
}

object ShuffleNative {
    init { System.loadLibrary("ignorant_shuffle") }
    external fun plan(keys: Array<String>, weights: DoubleArray, recent: Array<String>, played: Array<String>,
        cooldown: Int, finite: Boolean, avoid: String, unit: Double): DoubleArray
}

class NativePlanner : Planner {
    override fun choose(tracks: List<Track>, weights: DoubleArray, recent: List<String>, played: List<String>,
        settings: Settings, avoid: String): Choice {
        val result = ShuffleNative.plan(tracks.map { it.key }.toTypedArray(), weights, recent.toTypedArray(),
            played.toTypedArray(), settings.cooldown, settings.finite, avoid, kotlin.random.Random.nextDouble())
        return Choice(tracks.getOrNull(result[0].toInt()), result[1], result[2].toInt(), result[3].toInt(),
            result[4].toInt(), result[5] != 0.0, result[6].toInt())
    }
}

class ShuffleModel(private val planner: Planner) {
    var tracks: List<Track> = emptyList(); private set
    var settings = Settings(); private set
    var weights: Map<String, Double> = emptyMap(); private set
    var currentKey: String? = null; private set
    val recent = mutableListOf<String>()
    val played = linkedSetOf<String>()
    private val timeline = mutableListOf<String>()
    private var cursor = -1
    private var navigation: Int? = null
    private var preview: Choice? = null
    var choiceSequence: Long = 0; private set

    fun weight(track: Track) = weights[track.key] ?: settings.defaultWeight

    fun configure(settings: Settings, weights: Map<String, Double>) {
        settings.validate()
        require(weights.values.all { it.isFinite() && it >= 0 }) { "Weights must be finite and nonnegative" }
        val changedMode = settings.finite != this.settings.finite
        this.settings = settings
        this.weights = weights.toMap()
        if (changedMode) newPass()
        preview = null
    }

    fun catalog(value: List<Track>) {
        val changed = tracks.map { it.key }.sorted() != value.map { it.key }.sorted()
        tracks = value
        // A published roll is a commitment, not a request to draw again on queue
        // reorder/addition. Removal of its target requires an explicit new roll.
        if (changed && preview?.track?.let { chosen -> value.none { it.key == chosen.key } } != false) preview = null
    }

    fun confirmPlaying(track: Track, restarted: Boolean = false) {
        val key = track.key
        if (key == currentKey && !restarted) return
        val target = navigation
        if (target != null && timeline.getOrNull(target) == key) cursor = target
        else {
            while (timeline.size > cursor + 1) timeline.removeAt(timeline.lastIndex)
            timeline.add(key)
            if (timeline.size > 10001) timeline.removeAt(0)
            cursor = timeline.lastIndex
        }
        navigation = null
        currentKey = key
        recent.add(key)
        if (recent.size > 10001) recent.removeAt(0)
        if (settings.finite) played.add(key)
        preview = null
    }

    fun newPass() {
        played.clear()
        currentKey?.let { if (settings.finite) played.add(it) }
        preview = null
    }

    fun disconnect() {
        tracks = emptyList(); currentKey = null; recent.clear(); played.clear(); timeline.clear()
        cursor = -1; navigation = null; preview = null
    }

    fun next(reroll: Boolean = false): Choice {
        val old = preview
        if (!reroll && old != null) return old.copy(track = old.track?.let { t -> tracks.firstOrNull { it.key == t.key } })
        if (!reroll && cursor >= 0 && cursor < timeline.lastIndex) {
            val forward = tracks.firstOrNull { it.key == timeline[cursor + 1] && weight(it) > 0 }
            if (forward != null) return Choice(forward, historyIndex = cursor + 1).also { preview = it; choiceSequence++ }
        }
        return planner.choose(tracks, tracks.map(::weight).toDoubleArray(), recent.toList(), played.toList(),
            settings, if (reroll) old?.track?.key.orEmpty() else "").also { preview = it; choiceSequence++ }
    }

    fun previous(): Choice? {
        for (index in cursor - 1 downTo 0) {
            val track = tracks.firstOrNull { it.key == timeline[index] && weight(it) > 0 } ?: continue
            return Choice(track, historyIndex = index)
        }
        return null
    }

    fun requested(choice: Choice) { navigation = choice.historyIndex }
    fun cancelRequest() { navigation = null }
}

object WeightTransfer {
    fun parse(text: String): Map<String, Double> {
        val result = linkedMapOf<String, Double>()
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
            val parts = line.trimEnd('\r').split('\t', limit = 2)
            require(parts.size == 2) { "Line ${index + 1}: expected weight<TAB>key" }
            val number = parts[0].toDoubleOrNull()
            require(number != null && number.isFinite() && number >= 0) { "Line ${index + 1}: invalid weight" }
            require(parts[1].matches(Regex("android:v1:[a-f0-9]{64}"))) {
                "Line ${index + 1}: this version imports Android identity keys only; Windows paths cannot be mapped automatically"
            }
            result[parts[1]] = number
        }
        return result
    }

    fun write(weights: Map<String, Double>): String = buildString {
        appendLine("# IgnorantShuffle Android weights v1: weight<TAB>app-local track key (UTF-8)")
        weights.toSortedMap().forEach { (key, value) -> appendLine("$value\t$key") }
    }
}
