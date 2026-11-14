package dev.ignorantshuffle.companion

/** Worker-owned batch: retain the latest session snapshot and every session/day delta. */
internal class PendingListening {
    private val rows=linkedMapOf<Pair<String,String>,ListeningDelta>()
    fun add(delta: ListeningDelta) {
        val key=delta.session.id to delta.day
        val old=rows[key]
        rows[key]=if(old==null)delta else delta.copy(ms=old.ms+delta.ms,
            plays=old.plays+delta.plays,exits=old.exits+delta.exits)
    }
    fun values()=rows.values.toList()
    fun clear()=rows.clear()
}
