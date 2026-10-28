package dev.ignorantshuffle.companion

import java.io.File

/** Bounded persistent journal, also usable by JVM tests without Android stubs. */
class DiagnosticJournal(private val directory: File, private val limit: Long = 2_000_000) {
    private val current = File(directory, "events.log")
    private val previous = File(directory, "events.previous.log")
    var failure: String? = null; private set
    fun append(line: String) {
        try {
            directory.mkdirs()
            if (current.length() >= limit) {
                check(!previous.exists() || previous.delete()) { "Cannot remove previous log" }
                check(current.renameTo(previous)) { "Cannot rotate log" }
            }
            current.appendText(line.take(64_000) + "\n", Charsets.UTF_8)
            failure = null
        } catch (e: Exception) { failure = e.toString() }
    }
    fun contents(): String = try {
        listOf(previous, current).filter(File::exists).joinToString("\n") { it.readText(Charsets.UTF_8) }
    } catch (e: Exception) { failure = e.toString(); "LOG READ ERROR: $e" }
    fun tail(): List<String> = contents().lineSequence().filter(String::isNotBlank).toList().takeLast(200)
}
