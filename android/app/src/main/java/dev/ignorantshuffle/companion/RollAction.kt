package dev.ignorantshuffle.companion

/** Identifies the published choice, so an old notification cannot play a different roll. */
data class RollAction(val token: String, val sequence: Long, val trackKey: String) {
    fun matches(token: String, sequence: Long, choice: Choice): Boolean =
        this.token == token && this.sequence == sequence && choice.track?.key == trackKey
}
