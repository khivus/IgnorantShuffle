package dev.ignorantshuffle.companion

/** Small local preferences learned from observed listening, separate from saved base weights. */
data class SmartWeightOptions(val enabled: Boolean = false, val strength: Double = 1.0,
    val limit: Double = 0.15, val skips: Boolean = true, val completions: Boolean = true,
    val replays: Boolean = true, val volume: Boolean = true) {
    fun validate() {
        require(strength.isFinite() && strength in 0.25..2.0) { "Smart strength must be 0.25–2" }
        require(limit.isFinite() && limit in 0.05..0.25) { "Smart adjustment limit must be 5–25%" }
    }
    fun step(kind: SmartSignal): Double = if (!enabled) 0.0 else when(kind) {
        SmartSignal.EARLY_SKIP -> if(skips) -0.02 else 0.0
        SmartSignal.COMPLETED -> if(completions) 0.005 else 0.0
        SmartSignal.REPLAYED -> if(replays) 0.01 else 0.0
        SmartSignal.VOLUME_UP -> if(volume) 0.0025 else 0.0
    } * strength
    fun adjustment(score: Double, kind: SmartSignal, sessionGain: Double): Double {
        val step=step(kind)
        if(step==0.0)return 0.0
        val bounded=if(step>0) minOf(step,(0.015-sessionGain).coerceAtLeast(0.0)) else step
        val old=score.coerceIn(-limit,limit)
        return (old+bounded).coerceIn(-limit,limit)-score
    }
    fun factor(score: Double) = if(enabled) 1.0+score.coerceIn(-limit,limit) else 1.0
}
enum class SmartSignal { EARLY_SKIP, COMPLETED, REPLAYED, VOLUME_UP }
data class SmartFeedback(val session: String, val key: String, val kind: SmartSignal)
data class ListeningVolume(val type: Int, val control: Int, val max: Int, val value: Int, val route: String="")

/** Volume is a weak signal: one rise per session, after a stable baseline; routes reset it. */
class VolumeEvidence {
    private var last: ListeningVolume?=null
    private var stableSince=0L
    private var blockedUntil=0L
    fun reset(elapsed: Long=0) { last=null;stableSince=0;blockedUntil=elapsed+3000 }
    fun observe(value: ListeningVolume?, elapsed: Long): Boolean {
        val before=last
        if(value==null || value.control==0 || value.max<=0 || value.value !in 0..value.max) { reset(elapsed);return false }
        last=value
        if(before==null || before.type!=value.type || before.control!=value.control || before.max!=value.max || before.route!=value.route) {
            stableSince=elapsed;return false
        }
        return elapsed>=blockedUntil && elapsed-stableSince>=5000 && value.value>before.value
    }
}
