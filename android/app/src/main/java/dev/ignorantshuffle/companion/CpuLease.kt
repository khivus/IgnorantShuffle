package dev.ignorantshuffle.companion

interface LeaseHandle { fun acquire(timeout: Long); fun release() }

/** Renewable bounded leases: no wake lock outlives pause, disable or session loss. */
class CpuLease(private val handle: LeaseHandle, private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit, private val event: (String) -> Unit,
    private val failed: (Exception) -> Unit = {}) {
    private var needed = false
    private val renewal = Runnable { if (needed) acquire() }
    private fun acquire() {
        try {
            handle.acquire(600_000)
            cancel(renewal); schedule(renewal, 300_000)
            event("CPU lease acquired/renewed (10 minute safety timeout)")
        } catch (e: Exception) {
            needed = false; cancel(renewal); runCatching { handle.release() }; failed(e)
        }
    }
    fun update(active: Boolean) {
        if (needed == active) return
        needed = active
        if (active) acquire()
        else { cancel(renewal); handle.release(); event("CPU lease released") }
    }
}

data class ScheduledRoll(val source: String, val choice: Choice, val sequence: Long,
    val elapsedDeadline: Long, val uptimeDeadline: Long)

object HandoffPolicy {
    fun valid(roll: ScheduledRoll, sample: TimingSnapshot, currentSequence: Long, now: Long): Boolean =
        roll.sequence == currentSequence && EndTiming.due(sample, roll.source, now)
}

object CpuProtection {
    fun needed(automatic: Boolean, pending: Boolean, sample: TimingSnapshot, now: Long): Boolean =
        automatic && (pending || EndTiming.delayMillis(sample, now) != null)
}
