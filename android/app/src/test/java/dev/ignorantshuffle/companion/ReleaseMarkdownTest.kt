package dev.ignorantshuffle.companion

import org.junit.Assert.*
import org.junit.Test

class ReleaseMarkdownTest {
    @Test fun headingsListsQuotesAndDividersHaveReadableStructure() {
        val blocks = ReleaseMarkdown.blocks("# Release\n\n## Changes\n- One\n* Two\n1. Three\n> Preview\n---")
        assertEquals(MarkdownBlock.Line("Release", 1), blocks[0])
        assertEquals(MarkdownBlock.Line("Changes", 2), blocks[1])
        assertEquals(MarkdownBlock.Line("• One"), blocks[2])
        assertEquals(MarkdownBlock.Line("• Two"), blocks[3])
        assertEquals(MarkdownBlock.Line("1. Three"), blocks[4])
        assertEquals(MarkdownBlock.Line("Preview", quote = true), blocks[5])
        assertEquals(MarkdownBlock.Divider, blocks[6])
    }
    @Test fun emphasisLinksAndInlineCodeAreStyledWithoutRawMarkers() {
        val spans = ReleaseMarkdown.spans("**Bold** and *italic* with `v0.13.0` or [download](https://github.com/khivus/IgnorantShuffle)")
        assertEquals("Bold and italic with v0.13.0 or download", spans.joinToString("") { it.text })
        assertTrue(spans.first().bold)
        assertTrue(spans.any { it.italic && it.text == "italic" })
        assertTrue(spans.any { it.code && it.text == "v0.13.0" })
        assertEquals("https://github.com/khivus/IgnorantShuffle", spans.last().url)
    }
    @Test fun linksCanContainBoldLabelsAndUnclosedMarkupStaysReadable() {
        val spans = ReleaseMarkdown.spans("[**APK**](https://example.com/a.apk) and **unfinished")
        assertTrue(spans.first().bold)
        assertEquals("https://example.com/a.apk", spans.first().url)
        assertTrue(spans.last().text.endsWith("**unfinished"))
    }
    @Test fun htmlAndUnsafeLinkTargetsStayInert() {
        val text = "<script>alert(1)</script> [open](javascript:alert) [file](file:///private) [bad](https://user:password@example.com)"
        val spans = ReleaseMarkdown.spans(text)
        assertTrue(spans.all { it.url == null })
        assertEquals(text, spans.joinToString("") { it.text })
    }
    @Test fun downloadTablesKeepHeadersLinksAndRowsWithoutTheRuleLine() {
        val block = ReleaseMarkdown.blocks("| Download | Use |\n| :--- | ---: |\n| [APK](https://example.com/a.apk) | Android |\n| DLL | Windows |").single() as MarkdownBlock.Table
        assertEquals(3, block.rows.size)
        assertEquals(listOf("Download", "Use"), block.rows[0])
        assertEquals("https://example.com/a.apk", ReleaseMarkdown.spans(block.rows[1][0]).single().url)
    }
    @Test fun fencedCodePreservesWhitespaceAndMarkdownCharacters() {
        val block = ReleaseMarkdown.blocks("```powershell\n  echo **literal**\n```\nAfter")[0] as MarkdownBlock.Code
        assertEquals("  echo **literal**", block.text)
    }
    @Test fun filenamesKeepTheirUnderscores() {
        val name = "aimp_ignorant_shuffle-x64.dll"
        assertEquals(name, ReleaseMarkdown.spans(name).single().text)
        assertTrue(ReleaseMarkdown.spans("_emphasis_").single().italic)
    }
    @Test fun oversizedNotesAreBoundedAndExplainTruncation() {
        val blocks = ReleaseMarkdown.blocks((1..600).joinToString("\n") { "Line $it" })
        assertEquals(401, blocks.size)
        assertTrue((blocks.last() as MarkdownBlock.Line).text.contains("truncated"))
    }
}
