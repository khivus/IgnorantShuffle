package dev.ignorantshuffle.companion

/** Unknown is deliberately distinct from shuffle off, including while connecting. */
enum class AimpShuffleMode {
    UNKNOWN, OFF, ON;

    companion object {
        fun fromSession(mode: Int) = when (mode) {
            0 -> OFF
            1, 2 -> ON
            else -> UNKNOWN
        }
    }
}
