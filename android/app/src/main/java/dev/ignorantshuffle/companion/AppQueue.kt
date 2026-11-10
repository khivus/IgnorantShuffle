package dev.ignorantshuffle.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persist stable identities, never AIMP's session-scoped numeric queue IDs. */
data class QueueEntry(val id: String, val key: String, val title: String, val artist: String) {
    companion object {
        fun from(track: Track) = QueueEntry(UUID.randomUUID().toString(), track.key, track.title, track.artist)
        fun encode(entries: List<QueueEntry>): String = JSONArray().apply {
            entries.forEach { put(JSONObject().put("id", it.id).put("key", it.key).put("title", it.title).put("artist", it.artist)) }
        }.toString()
        fun decode(text: String): List<QueueEntry> = JSONArray(text).let { array ->
            (0 until array.length()).map { i -> array.getJSONObject(i).let {
                QueueEntry(it.getString("id"), it.getString("key"), it.getString("title"), it.getString("artist"))
            } }
        }
    }
}

object ListeningBalance {
    fun mean(counts: Map<String, Long>, catalog: List<Track>): Double =
        catalog.distinctBy { it.key }.map { (counts[it.key] ?: 0L).toDouble() }.average().takeIf { it.isFinite() } ?: 0.0
    fun factor(key: String, counts: Map<String, Long>, catalog: List<Track>): Double = factor(key, counts, mean(counts, catalog))
    fun factor(key: String, counts: Map<String, Long>, mean: Double): Double {
        return ((mean + 1.0) / ((counts[key] ?: 0L) + 1.0)).coerceIn(0.25, 4.0)
    }
}
