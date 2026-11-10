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
class ListeningStatistics(private val feedback: (SmartFeedback) -> Unit = {},
    private val emit: (ListeningDelta) -> Unit) {
    private data class Sample(val p: Playback, val elapsed: Long, val position: Double)
    private var last: Sample? = null
    private var session: ListeningSession? = null
    private var duration = -1L
    private var observedMedia=0.0
    private var lastCreditAt=-1L
    private var lastCreditPosition=0.0
    private var completed=false
    private var completionPossible=true
    private var pendingReplay=false
    private var pendingVolume=false
    private var rewardedVolume=false
    private val volume=VolumeEvidence()
    var collecting = false; private set

    fun collecting(enabled: Boolean) { collecting = enabled; last = null; if(!enabled)completionPossible=false;volumeRouteChanged() }
    private fun resetSignals() {
        observedMedia=0.0;lastCreditAt=-1;lastCreditPosition=0.0;completed=false;completionPossible=true;pendingReplay=false
        pendingVolume=false;rewardedVolume=false;volume.reset()
    }
    fun reset() { last = null; session = null; duration = -1; resetSignals() }
    fun disconnect() { reset() }
    // Reactive settling/correction fragments cannot supply either end of an interval.
    fun reject() { last = null;volumeRouteChanged() }
    fun volumeRouteChanged(elapsed: Long=0) { volume.reset(elapsed);pendingVolume=false }

    fun observeVolume(p: Playback, value: ListeningVolume?, elapsed: Long) {
        val active=session
        if(!collecting || active==null || active.key!=p.track?.key || p.state!=3 || last==null) {
            volumeRouteChanged(elapsed);return
        }
        if(volume.observe(value,elapsed) && !rewardedVolume) pendingVolume=true
        rewardVolume()
    }
    private fun rewardVolume() {
        val active=session ?: return
        if(active.qualified && pendingVolume && !rewardedVolume) {
            rewardedVolume=true;pendingVolume=false
            feedback(SmartFeedback(active.id,active.key,SmartSignal.VOLUME_UP))
        }
    }

    fun observe(p: Playback, elapsed: Long, wall: Long, restarted: Boolean = false,
        userReplay: Boolean = false, automaticTransition: Boolean = false) {
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
            observedMedia>=duration*0.9 && old.position >= duration - 1500 && p.position in 0..1000 && p.updatedAt > old.p.updatedAt
        if (session?.key != track.key || restarted || naturalRepeat) {
            val previous=session
            val completedPrevious=completed
            if(previous!=null && previous.key!=track.key && !previous.qualified && previous.listeningMs>=2000 &&
                lastCreditAt>=0 && elapsed-lastCreditAt in 0..2000 && (duration<=0 || lastCreditPosition<duration-1500)) {
                feedback(SmartFeedback(previous.id,previous.key,SmartSignal.EARLY_SKIP))
            }
            close(wall, early = !naturalRepeat)
            resetSignals()
            if (p.state != 3) return
            session = ListeningSession(UUID.randomUUID().toString(), track.key, track.title, track.artist, wall, wall)
            pendingReplay=userReplay || (naturalRepeat && completedPrevious && !automaticTransition)
        }
        duration = p.duration
        if (p.state != 3 || p.position < 0 || p.updatedAt <= 0 || p.updatedAt > elapsed || !p.speed.isFinite() || p.speed <= 0) {
            last = null; volumeRouteChanged(elapsed);return
        }
        val position = p.position.toDouble() + (elapsed - p.updatedAt) * p.speed
        if (duration > 0 && position > duration + 1000) { last = null; return }
        val sample = Sample(p, elapsed, position.coerceAtMost(if (duration > 0) duration.toDouble() else Double.MAX_VALUE))
        val before = last
        last = sample
        if (before == null || before.p.track?.key != track.key) return
        val dt = elapsed - before.elapsed
        val dp = sample.position - before.position
        if(dt==0L && dp==0.0)return
        // A stalled main thread, pause gap, stale/backward clock, seek or speed change is not listening evidence.
        if (dt !in 1..5000 || before.p.speed != p.speed || dp <= 0 || abs(dp - dt * p.speed) > 250) {
            lastCreditAt=-1
            if(dt !in 1..5000 || dp<=0 || before.p.speed==p.speed)completionPossible=false
            return
        }
        val credit = minOf(dt, (dp / p.speed).toLong()).coerceAtLeast(0)
        if (credit == 0L) return
        val active = session ?: return
        val total = active.listeningMs + credit
        val threshold = if (duration > 0) minOf(30_000, (duration / 2).coerceAtLeast(1)) else 30_000
        val qualifies = !active.qualified && total >= threshold
        val updated = active.copy(updated = wall, listeningMs = total, qualified = active.qualified || qualifies)
        session = updated
        observedMedia+=credit*p.speed.toDouble();lastCreditAt=elapsed;lastCreditPosition=sample.position
        // Split intervals at local midnight, assigning the play to the qualifying observation's day.
        val end = Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault())
        val midnight = end.toLocalDate().atStartOfDay(end.zone).toInstant().toEpochMilli()
        val todayMs = minOf(credit, (wall - midnight).coerceAtLeast(0))
        if (todayMs < credit) emit(ListeningDelta(updated, end.toLocalDate().minusDays(1).toString(), credit - todayMs))
        emit(ListeningDelta(updated, end.toLocalDate().toString(), todayMs, if (qualifies) 1 else 0))
        if(updated.qualified && pendingReplay) {
            pendingReplay=false;feedback(SmartFeedback(updated.id,updated.key,SmartSignal.REPLAYED))
        }
        rewardVolume()
        if(!completed && completionPossible && duration>0 && observedMedia>=duration*0.9 && sample.position>=duration-1500) {
            completed=true;feedback(SmartFeedback(updated.id,updated.key,SmartSignal.COMPLETED))
        }
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
