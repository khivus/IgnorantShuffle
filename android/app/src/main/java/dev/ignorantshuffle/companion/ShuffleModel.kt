package dev.ignorantshuffle.companion

import java.security.MessageDigest
import kotlin.math.pow

data class Track(val queueId: Long, val mediaId: String?, val uri: String?, val title: String, val artist: String,
    val statisticsIdentity: String?=null) {
    // Conservative app-local identity. Do not persist numeric queue IDs or guess file paths.
    val key: String = statisticsIdentity ?: run {
        val text = listOf(mediaId ?: uri ?: "unidentified", title, artist).joinToString("\u0000")
        "android:v1:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    val selectable get() = mediaId != null || uri != null || queueId >= 0
}

data class Settings(val defaultWeight: Double = 5.0, val cooldown: Int = 0, val finite: Boolean = false,
    val minimumWeight: Double = 1.0) {
    fun validate() {
        require(defaultWeight.isFinite() && defaultWeight >= 0) { "Default weight must be finite and nonnegative" }
        require(cooldown in 0..10000) { "Cooldown must be between 0 and 10000" }
        require(minimumWeight.isFinite() && minimumWeight > 0) { "Minimum weight must be finite and greater than 0" }
    }
}

data class Choice(val track: Track?, val probability: Double = 0.0, val eligible: Int = 0,
    val excluded: Int = 0, val blocked: Int = 0, val cooldownFallback: Boolean = false,
    val remaining: Int = 0, val historyIndex: Int? = null, val queueEntryId: String? = null)

fun interface Planner {
    fun choose(tracks: List<Track>, weights: DoubleArray, recent: List<String>, played: List<String>,
        settings: Settings, avoid: String): Choice
}

object ShuffleNative {
    init { System.loadLibrary("ignorant_shuffle") }
    external fun coreVersion(): String
    external fun learningWeight(base: Double,minimum: Double,plays: Long,mean: Double,balance: Boolean,
        balanceStrength: Double,smart: Boolean,score: Double,limit: Double): Double
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

class ShuffleModel(private val planner: Planner, private val nativeLearning: Boolean=false) {
    var tracks: List<Track> = emptyList(); private set
    var settings = Settings(); private set
    var weights: Map<String, Double> = emptyMap(); private set
    var currentKey: String? = null; private set
    val recent = mutableListOf<String>()
    val played = linkedSetOf<String>()
    private val timeline = mutableListOf<String>()
    private var cursor = -1
    private var navigation: Int? = null
    private var requestedQueue: String? = null
    var queue: List<QueueEntry> = emptyList(); private set
    private var balanced = false
    private var playCounts: Map<String, Long> = emptyMap()
    private var balanceMean = 0.0
    private var balanceStrength=1.0
    private var smart=SmartWeightOptions()
    private var smartScores: Map<String,Double> = emptyMap()
    private var preview: Choice? = null
    var choiceSequence: Long = 0; private set

    fun weight(track: Track) = weights[track.key] ?: settings.defaultWeight
    fun eligible(track: Track) = weight(track) >= settings.minimumWeight

    fun balance(enabled: Boolean, counts: Map<String, Long>, strength: Double = 1.0) {
        require(strength.isFinite() && strength in 0.25..1.0)
        if (balanced == enabled && playCounts == counts && balanceStrength==strength) return
        val changedMode = balanced != enabled || balanceStrength!=strength
        balanceStrength=strength
        balanced = enabled; playCounts = counts.toMap(); balanceMean = ListeningBalance.mean(playCounts, tracks)
        // Newly learned totals affect the next roll; keep the currently published commitment.
        if (changedMode) preview = null
    }
    fun smartWeights(options: SmartWeightOptions, scores: Map<String,Double>) {
        options.validate()
        require(scores.values.all { it.isFinite() })
        if(smart!=options) preview=null
        smart=options;smartScores=scores.toMap()
    }
    fun learningFactor(track: Track): Double = (if(balanced) ListeningBalance.factor(track.key,playCounts,balanceMean).pow(balanceStrength) else 1.0) * smart.factor(smartScores[track.key]?:0.0)
    fun selectionWeight(track: Track): Double = when {
        nativeLearning -> ShuffleNative.learningWeight(weight(track),settings.minimumWeight,playCounts[track.key]?:0L,balanceMean,
            balanced,balanceStrength,smart.enabled,smartScores[track.key]?:0.0,smart.limit)
        !eligible(track) -> 0.0
        !balanced && !smart.enabled -> weight(track)
        else -> (weight(track) * learningFactor(track)).coerceAtMost(Double.MAX_VALUE / 4)
    }

    fun setQueue(entries: List<QueueEntry>) {
        if (entries == queue) return
        queue = entries.toList(); preview = null
    }

    fun configure(settings: Settings, weights: Map<String, Double>, preservePreview: Boolean = false) {
        settings.validate()
        require(weights.values.all { it.isFinite() && it >= 0 }) { "Weights must be finite and nonnegative" }
        val changedMode = settings.finite != this.settings.finite
        val keep = preservePreview && settings == this.settings && preview?.track?.let { t ->
            (this.weights[t.key] ?: this.settings.defaultWeight) == (weights[t.key] ?: settings.defaultWeight)
        } == true
        this.settings = settings
        this.weights = weights.toMap()
        if (changedMode) newPass()
        if (!keep) preview = null
    }

    fun catalog(value: List<Track>) {
        val changed = tracks.map { it.key }.sorted() != value.map { it.key }.sorted()
        tracks = value
        balanceMean = ListeningBalance.mean(playCounts, tracks)
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
        requestedQueue?.let { id ->
            if (queue.any { it.id == id && it.key == key }) {
                queue = queue.filterNot { it.id == id }; requestedQueue = null
            }
        }
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
        cursor = -1; navigation = null; requestedQueue = null; preview = null
    }

    fun next(reroll: Boolean = false): Choice {
        val old = preview
        if (!reroll && old != null) return old.copy(track = old.track?.let { t -> tracks.firstOrNull { it.key == t.key } })
        queue.firstNotNullOfOrNull { entry -> tracks.firstOrNull { it.key == entry.key && it.selectable }?.let {
            Choice(it, remaining = tracks.filter(::eligible).count { t -> t.key !in played }, queueEntryId = entry.id)
        } }?.let { preview = it; choiceSequence++; return it }
        if (!reroll && cursor >= 0 && cursor < timeline.lastIndex) {
            val forward = tracks.firstOrNull { it.key == timeline[cursor + 1] && eligible(it) }
            if (forward != null) return Choice(forward, historyIndex = cursor + 1).also { preview = it; choiceSequence++ }
        }
        // Apply the eligibility cutoff before native cooldown/finite-pass rules,
        // while preserving the actual stored weights and their relative chances.
        val selectionWeights = tracks.map(::selectionWeight).toDoubleArray()
        return planner.choose(tracks, selectionWeights, recent.toList(), played.toList(),
            settings, if (reroll) old?.track?.key.orEmpty() else "").also { preview = it; choiceSequence++ }
    }

    fun previous(): Choice? {
        for (index in cursor - 1 downTo 0) {
            val track = tracks.firstOrNull { it.key == timeline[index] && eligible(it) } ?: continue
            return Choice(track, historyIndex = index)
        }
        return null
    }

    fun requested(choice: Choice) { navigation = choice.historyIndex; requestedQueue = choice.queueEntryId }
    fun cancelRequest() { navigation = null; requestedQueue = null }
}
