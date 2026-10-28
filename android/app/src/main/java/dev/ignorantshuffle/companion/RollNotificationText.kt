package dev.ignorantshuffle.companion

import java.util.Locale

data class NotificationText(val title: String, val body: String)
object RollNotificationText {
    fun create(choice: Choice, weight: Double, settings: Settings, automatic: Boolean,
        stats: RollStats, sequence: Long): NotificationText? {
        val track = choice.track ?: return null
        val lines = mutableListOf<String>()
        if (stats.artist && track.artist.isNotBlank()) lines += track.artist
        if (stats.weight) lines += "Weight: $weight"
        if (stats.chance) lines += if (choice.historyIndex != null) "Listening history" else
            "Chance at roll: ${String.format(Locale.ROOT, "%.1f", choice.probability * 100)}%"
        if (stats.pool) lines += "Eligible: ${choice.eligible} · excluded: ${choice.excluded}"
        if (stats.cooldown) lines += "Cooling: ${choice.blocked}" + if (choice.cooldownFallback) " · short-list fallback" else ""
        if (stats.remaining && settings.finite) lines += "Remaining this pass: ${choice.remaining}"
        if (stats.mode) lines += "${if (automatic) "Timed" else "Manual"} · ${if (settings.finite) "Finite pass" else "Endless"}"
        return NotificationText("Next: ${track.title.take(160)}", "Roll #$sequence" +
            if (lines.isEmpty()) " · Open IgnorantShuffle" else "\n" + lines.joinToString("\n"))
    }
}
