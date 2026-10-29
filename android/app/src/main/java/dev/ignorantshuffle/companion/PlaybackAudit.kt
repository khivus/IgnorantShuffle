package dev.ignorantshuffle.companion

data class PlaybackAuditResult(val matched: Boolean, val message: String)

/** Bounded, observation-only check. Never retries or changes a selection. */
class PlaybackAudit {
    companion object {
        fun handoffDue(plan: ScheduledRoll?, playback: Playback, now: Long): Boolean =
            plan?.choice?.track != null && playback.state == 3 && playback.track != null &&
                playback.track.key != plan.source && now >= plan.elapsedDeadline - 1000
    }
    private data class Expected(val before: Playback, val target: Track, val sequence: Long, val started: Long)
    private var expected: Expected? = null
    private var wrongKey: String? = null
    private var wrongSince = 0L
    val active get() = expected != null

    fun arm(before: Playback, target: Track, sequence: Long, now: Long) {
        expected = Expected(before, target, sequence, now)
        wrongKey = null
    }
    fun cancel() { expected = null; wrongKey = null }

    fun observe(playback: Playback, now: Long): PlaybackAuditResult? {
        val e = expected ?: return null
        val matched = PlaybackEvidence.confirmed("Selected track", e.target.key, e.before, playback)
        val wrong = playback.state == 3 && playback.track != null &&
            playback.track.key != e.target.key && playback.track.key != e.before.track?.key
        if (wrong) {
            if (wrongKey != playback.track?.key) { wrongKey = playback.track?.key; wrongSince = now }
        } else wrongKey = null
        val expired = now - e.started >= 8000
        if (!matched && !expired && !(wrong && now - wrongSince >= 750)) return null
        cancel()
        val message = if (matched) "Roll #${e.sequence}: confirmed ${e.target.title} is playing"
            else "Playback issue · roll #${e.sequence}: expected ${e.target.title}; observed ${playback.title} " +
                "(state ${playback.state}). ${if (wrong) "A different track played next." else "No confirming playback within 8 seconds."}"
        return PlaybackAuditResult(matched, message)
    }
}
