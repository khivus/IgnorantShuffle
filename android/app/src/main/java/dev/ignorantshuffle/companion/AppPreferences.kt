package dev.ignorantshuffle.companion

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Accent(val argb: Long, val label: String) {
    ORANGE(0xFFFF8800, "AIMP orange"), BLUE(0xFF1769AA, "Blue"), TEAL(0xFF00796B, "Teal"),
    PURPLE(0xFF7452A8, "Purple"), ROSE(0xFFB04465, "Rose"), GREEN(0xFF477C32, "Green")
}
data class RollStats(val artist: Boolean = true, val weight: Boolean = true, val chance: Boolean = true,
    val pool: Boolean = false, val cooldown: Boolean = false, val remaining: Boolean = false, val mode: Boolean = false)
data class AppPreferences(val logging: Boolean = true, val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.ORANGE, val notifyRoll: Boolean = false, val stats: RollStats = RollStats(),
    val notificationVisibility: RollVisibility = RollVisibility.ALWAYS, val leadMs: Long = EndTiming.LEAD_MS,
    val automatic: Boolean = true, val debugPlayback: Boolean = false,
    val reactivePlayback: Boolean = false, val correctionDelayMs: Long = ReactivePlayback.SETTLE_MS,
    val notificationNext: Boolean = true, val notificationPrevious: Boolean = true,
    val balanceListening: Boolean = false)

data class NotificationButtons(val next: Boolean, val previous: Boolean) {
    companion object {
        fun create(preferences: AppPreferences, pending: Boolean, hasNext: Boolean, hasPrevious: Boolean) =
            NotificationButtons(preferences.notifyRoll && !pending && hasNext && preferences.notificationNext,
                preferences.notifyRoll && !pending && hasPrevious && preferences.notificationPrevious)
    }
}

enum class RollVisibility(val label: String) {
    ALWAYS("Always while connected"), PLAYING_OR_PAUSED("Playing or paused"), PLAYING_ONLY("Playing only");
    fun allows(state: Int): Boolean = when (this) {
        ALWAYS -> true
        PLAYING_OR_PAUSED -> state == 3 || state == 2
        PLAYING_ONLY -> state == 3
    }
}
