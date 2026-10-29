package dev.ignorantshuffle.companion

import java.net.URI

data class MarkdownSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false,
    val code: Boolean = false, val url: String? = null)

sealed interface MarkdownBlock {
    data class Line(val text: String, val heading: Int = 0, val quote: Boolean = false) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/** Deliberately small Markdown subset; HTML and images remain literal text. */
object ReleaseMarkdown {
    private val tokens = Regex("\\[([^]\\n]+)]\\(([^\\s)]+)\\)|\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\*([^*\\n]+)\\*|(?<!\\w)_([^_\\n]+)_(?!\\w)")
    fun spans(text: String, depth: Int = 0): List<MarkdownSpan> {
        if (depth >= 4) return listOf(MarkdownSpan(text))
        val result = mutableListOf<MarkdownSpan>()
        var end = 0
        tokens.findAll(text).forEach { match ->
            if (match.range.first > end) result += MarkdownSpan(text.substring(end, match.range.first))
            val g = match.groupValues
            result += when {
                g[1].isNotEmpty() -> {
                    val url = runCatching { URI(g[2]) }.getOrNull()
                    if (url?.scheme in listOf("https", "http") && !url?.host.isNullOrBlank() && url?.rawUserInfo == null)
                        spans(g[1], depth + 1).map { it.copy(url = g[2]) }
                    else listOf(MarkdownSpan(match.value))
                }
                g[3].isNotEmpty() || g[4].isNotEmpty() -> spans(g[3].ifEmpty { g[4] }, depth + 1).map { it.copy(bold = true) }
                g[5].isNotEmpty() -> listOf(MarkdownSpan(g[5], code = true))
                else -> spans(g[6].ifEmpty { g[7] }, depth + 1).map { it.copy(italic = true) }
            }
            end = match.range.last + 1
        }
        if (end < text.length) result += MarkdownSpan(text.substring(end))
        return result
    }

    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    private fun tableRule(line: String) = line.contains('|') && cells(line).all { it.matches(Regex(":?-{3,}:?")) }

    fun blocks(text: String): List<MarkdownBlock> {
        val source = text.take(100_000).lines()
        val lines = source.take(400)
        val result = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i++]; val line = raw.trim()
            when {
                line.isBlank() -> Unit
                line.startsWith("```") -> {
                    val code = mutableListOf<String>()
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    if (i < lines.size) i++
                    result += MarkdownBlock.Code(code.joinToString("\n"))
                }
                line.contains('|') && i < lines.size && tableRule(lines[i].trim()) -> {
                    i++
                    val rows = mutableListOf(cells(line).take(8))
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++]).take(8)
                    result += MarkdownBlock.Table(rows)
                }
                line.matches(Regex("([-*_])(?:\\s*\\1){2,}")) -> result += MarkdownBlock.Divider
                else -> {
                    val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
                    result += when {
                        heading != null -> MarkdownBlock.Line(heading.groupValues[2], heading.groupValues[1].length)
                        line.startsWith("> ") -> MarkdownBlock.Line(line.drop(2), quote = true)
                        line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> MarkdownBlock.Line("• ${line.drop(2)}")
                        else -> MarkdownBlock.Line(line)
                    }
                }
            }
        }
        if (source.size > lines.size || text.length > 100_000) result += MarkdownBlock.Line("Notes truncated. Open the full release on GitHub.")
        return result
    }
}
