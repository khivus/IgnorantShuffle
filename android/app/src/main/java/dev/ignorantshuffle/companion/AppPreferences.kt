package dev.ignorantshuffle.companion

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Accent { ORANGE, BLUE, TEAL, PURPLE, ROSE, GREEN }
data class RollStats(val artist: Boolean = true, val weight: Boolean = true, val chance: Boolean = true,
    val pool: Boolean = false, val cooldown: Boolean = false, val remaining: Boolean = false, val mode: Boolean = false)
data class AppPreferences(val logging: Boolean = true, val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.ORANGE, val notifyRoll: Boolean = false, val stats: RollStats = RollStats())
