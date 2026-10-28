package dev.ignorantshuffle.companion

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Accent(val argb: Long, val label: String) {
    ORANGE(0xFFFF8800, "AIMP orange"), BLUE(0xFF1769AA, "Blue"), TEAL(0xFF00796B, "Teal"),
    PURPLE(0xFF7452A8, "Purple"), ROSE(0xFFB04465, "Rose"), GREEN(0xFF477C32, "Green")
}
data class RollStats(val artist: Boolean = true, val weight: Boolean = true, val chance: Boolean = true,
    val pool: Boolean = false, val cooldown: Boolean = false, val remaining: Boolean = false, val mode: Boolean = false)
data class AppPreferences(val logging: Boolean = true, val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.ORANGE, val notifyRoll: Boolean = false, val stats: RollStats = RollStats())
