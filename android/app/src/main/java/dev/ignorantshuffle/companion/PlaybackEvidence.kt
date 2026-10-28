package dev.ignorantshuffle.companion

data class Playback(val state: Int = 0, val title: String = "No track", val artist: String = "",
    val position: Long = -1, val duration: Long = -1, val speed: Float = 0f, val updatedAt: Long = 0,
    val track: Track? = null)

object PlaybackEvidence {
    fun confirmed(label: String, target: String?, before: Playback, now: Playback): Boolean = when (label) {
        "Play" -> before.state != 3 && now.state == 3
        "Pause" -> before.state != 2 && now.state == 2
        else -> target != null && now.state == 3 && now.track?.key == target &&
            (before.track?.key != target || before.state != 3 || (before.position >= 3000 && now.position in 0..999))
    }
}
