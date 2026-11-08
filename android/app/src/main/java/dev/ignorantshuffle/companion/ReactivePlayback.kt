package dev.ignorantshuffle.companion

data class ReactiveRoll(val source: String, val choice: Choice, val sequence: Long)

sealed interface ReactiveDecision {
    data class Accept(val manualOverride: Boolean = false) : ReactiveDecision
    data object Wait : ReactiveDecision
    data class Correct(val roll: ReactiveRoll, val rejected: String) : ReactiveDecision
}

/** Captures a roll before AIMP advances. Repeated callbacks never count as a second selection. */
class ReactivePlayback {
    companion object {
        const val SETTLE_MS = 200L
        const val MAX_DELAY_MS = 2000L
        const val OVERRIDE_MS = 5000L
        const val PAUSE_GRACE_MS = 1000L
    }
    var roll: ReactiveRoll? = null; private set
    private var candidate: String? = null
    private var candidateSince = 0L
    private var correction: ReactiveDecision.Correct? = null
    private var rejected: String? = null
    private var overrideUntil = 0L
    private var pauseSince: Long? = null
    val correcting get() = correction != null
    val waiting get() = candidate != null || correcting || pauseSince != null

    fun timedPlaybackReady(playback: Playback, acceptedKey: String?): Boolean =
        !waiting && playback.state == 3 && playback.track != null && playback.track.key == acceptedKey

    /** Honor sub-100 ms settings instead of rounding them to the confirmation poll interval. */
    fun nextCheckDelayMillis(now: Long, settleMs: Long): Long = when {
        candidate != null -> settleMs - (now - candidateSince)
        pauseSince != null -> PAUSE_GRACE_MS - (now - requireNotNull(pauseSince))
        else -> 100L
    }.coerceIn(1L, 100L)

    fun arm(source: String, choice: Choice, sequence: Long) {
        if (correcting) return
        val next = ReactiveRoll(source, choice, sequence)
        if (roll != next) candidate = null
        roll = next
    }

    fun reset() {
        roll = null; candidate = null; correction = null; rejected = null; overrideUntil = 0; pauseSince = null
    }

    fun observe(playback: Playback, now: Long, settleMs: Long = SETTLE_MS): ReactiveDecision {
        require(settleMs in 0..MAX_DELAY_MS) { "Correction delay must be between 0 and 2000 ms" }
        val key = playback.track?.key
        correction?.let { sent ->
            if (playback.state != 3 || key != sent.roll.choice.track?.key) return ReactiveDecision.Wait
            // The rejected track must start again AFTER our target was confirmed.
            rejected = sent.rejected; overrideUntil = now + OVERRIDE_MS
            correction = null; roll = null
            return ReactiveDecision.Accept()
        }
        if (playback.state != 3 || key == null) {
            candidate = null
            // AIMP emits PAUSED between tracks, sometimes before metadata changes.
            // Preserve the captured roll/override through that gap, without sending
            // commands while paused. A sustained pause cancels it and releases CPU.
            if (playback.state == 2 && (roll != null || rejected != null)) {
                val started = pauseSince ?: now.also { pauseSince = it }
                if (now - started < PAUSE_GRACE_MS) return ReactiveDecision.Wait
                reset()
            } else {
                pauseSince = null
                if (playback.state == 1 || playback.state == 7) reset()
            }
            return ReactiveDecision.Accept()
        }
        pauseSince = null
        val expected = roll ?: return ReactiveDecision.Accept()
        if (key == expected.source || key == expected.choice.track?.key) {
            candidate = null
            return ReactiveDecision.Accept()
        }
        if (key == rejected && now <= overrideUntil) {
            rejected = null; candidate = null; roll = null
            return ReactiveDecision.Accept(manualOverride = true)
        }
        if (candidate != key) { candidate = key; candidateSince = now }
        if (now - candidateSince < settleMs) return ReactiveDecision.Wait
        candidate = null; roll = null
        return ReactiveDecision.Correct(expected, key).also { correction = it }
    }
}
