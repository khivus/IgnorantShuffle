package dev.ignorantshuffle.companion

import kotlin.math.ceil

data class TimingSnapshot(val key: String?, val state: Int, val position: Long, val duration: Long,
    val speed: Float, val updatedAt: Long)

object EndTiming {
    const val LEAD_MS = 300L
    // One deadline per playing track, not a session-polling loop. A very late
    // handler must not take over an ambiguous end/stop/repeat transition.
    fun delayMillis(sample: TimingSnapshot, now: Long): Long? {
        if (sample.key == null || sample.state != 3 || sample.position < 0 || sample.duration <= LEAD_MS ||
            !sample.speed.isFinite() || sample.speed <= 0 || sample.updatedAt <= 0 || sample.updatedAt > now) return null
        val position = sample.position.toDouble() + (now - sample.updatedAt) * sample.speed.toDouble()
        if (!position.isFinite() || position > sample.duration.toDouble() + 1000) return null
        val delay = ceil((sample.duration - LEAD_MS - position) / sample.speed)
        return delay.coerceIn(0.0, Long.MAX_VALUE.toDouble() / 2).toLong()
    }
    fun due(sample: TimingSnapshot, expectedKey: String, now: Long): Boolean =
        sample.key == expectedKey && delayMillis(sample, now) == 0L
}
