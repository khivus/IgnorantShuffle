package dev.ignorantshuffle.companion

/** Identifies the published choice, so an old notification cannot play a different roll. */
data class RollAction(val token: String, val sequence: Long, val trackKey: String) {
    fun matches(token: String, sequence: Long, choice: Choice): Boolean =
        this.token == token && this.sequence == sequence && choice.track?.key == trackKey
}

/** Binds Previous to the source and exact history entry displayed by the notification. */
data class PreviousRollAction(val token: String, val sequence: Long, val sourceKey: String,
    val trackKey: String, val historyIndex: Int) {
    fun matches(token: String, sequence: Long, sourceKey: String?, previous: Choice?): Boolean =
        this.token == token && this.sequence == sequence && this.sourceKey == sourceKey &&
            previous?.track?.key == trackKey && previous.historyIndex == historyIndex
}
