package dev.ignorantshuffle.companion

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

data class ListeningSession(val id: String, val key: String, val title: String, val artist: String,
    val started: Long, val updated: Long, val listeningMs: Long = 0, val qualified: Boolean = false,
    val earlyExit: Boolean = false)
data class ListeningDelta(val session: ListeningSession, val day: String, val ms: Long = 0,
    val plays: Int = 0, val exits: Int = 0)

/** Main-thread reducer. No wall-clock extrapolation across missing observations or process starts. */
class ListeningStatistics(private val emit: (ListeningDelta) -> Unit) {
    private data class Sample(val p: Playback, val elapsed: Long, val position: Double)
    private var last: Sample? = null
    private var session: ListeningSession? = null
    private var duration = -1L
    var collecting = false; private set

    fun collecting(enabled: Boolean) { collecting = enabled; last = null }
    fun reset() { last = null; session = null; duration = -1 }
    fun disconnect() { reset() }
    // Reactive settling/correction fragments cannot supply either end of an interval.
    fun reject() { last = null }

    fun observe(p: Playback, elapsed: Long, wall: Long, restarted: Boolean = false) {
        if (!collecting) {
            if (session?.key != p.track?.key) reset()
            last = null; return
        }
        val track = p.track
        if (track == null || p.state == 1 || p.state == 0) {
            close(wall, early = p.state == 1); return
        }
        val old = last
        val naturalRepeat = old != null && old.p.track?.key == track.key && duration > 0 &&
            old.position >= duration - 2000 && p.position in 0..1000 && p.updatedAt > old.p.updatedAt
        if (session?.key != track.key || restarted || naturalRepeat) {
            close(wall, early = !naturalRepeat)
            if (p.state != 3) return
            session = ListeningSession(UUID.randomUUID().toString(), track.key, track.title, track.artist, wall, wall)
        }
        duration = p.duration
        if (p.state != 3 || p.position < 0 || p.updatedAt <= 0 || p.updatedAt > elapsed || !p.speed.isFinite() || p.speed <= 0) {
            last = null; return
        }
        val position = p.position.toDouble() + (elapsed - p.updatedAt) * p.speed
        if (duration > 0 && position > duration + 1000) { last = null; return }
        val sample = Sample(p, elapsed, position.coerceAtMost(if (duration > 0) duration.toDouble() else Double.MAX_VALUE))
        val before = last
        last = sample
        if (before == null || before.p.track?.key != track.key) return
        val dt = elapsed - before.elapsed
        val dp = sample.position - before.position
        // A stalled main thread, pause gap, stale/backward clock, seek or speed change is not listening evidence.
        if (dt !in 1..5000 || before.p.speed != p.speed || dp <= 0 || abs(dp - dt * p.speed) > 250) return
        val credit = minOf(dt, (dp / p.speed).toLong()).coerceAtLeast(0)
        if (credit == 0L) return
        val active = session ?: return
        val total = active.listeningMs + credit
        val threshold = if (duration > 0) minOf(30_000, (duration / 2).coerceAtLeast(1)) else 30_000
        val qualifies = !active.qualified && total >= threshold
        val updated = active.copy(updated = wall, listeningMs = total, qualified = active.qualified || qualifies)
        session = updated
        // Split intervals at local midnight, assigning the play to the qualifying observation's day.
        val end = Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault())
        val midnight = end.toLocalDate().atStartOfDay(end.zone).toInstant().toEpochMilli()
        val todayMs = minOf(credit, (wall - midnight).coerceAtLeast(0))
        if (todayMs < credit) emit(ListeningDelta(updated, end.toLocalDate().minusDays(1).toString(), credit - todayMs))
        emit(ListeningDelta(updated, end.toLocalDate().toString(), todayMs, if (qualifies) 1 else 0))
    }

    private fun close(wall: Long, early: Boolean) {
        val active = session
        // Unknown end/disconnect is never labeled an early exit. Small end leads are tolerated.
        if (active != null && active.listeningMs > 0 && early && !active.qualified &&
            (duration <= 0 || (last?.position ?: 0.0) < duration - 1500)) {
            emit(ListeningDelta(active.copy(updated = wall, earlyExit = true), day(wall), exits = 1))
        }
        last = null; session = null
    }
    companion object {
        fun day(wall: Long): String = Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}

data class TrackStatistics(val key: String, val title: String, val artist: String, val ms: Long, val plays: Long, val exits: Long)
data class StatisticsSnapshot(val tracks: List<TrackStatistics> = emptyList(), val history: List<ListeningSession> = emptyList(),
    val days: Map<String, Long> = emptyMap(), val shuffle: Map<String, Long> = emptyMap()) {
    val listeningMs get() = tracks.sumOf { it.ms }
    val plays get() = tracks.sumOf { it.plays }
    val exits get() = tracks.sumOf { it.exits }
}
