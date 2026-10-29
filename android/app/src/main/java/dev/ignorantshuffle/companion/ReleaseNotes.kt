package dev.ignorantshuffle.companion

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val spans = remember(text) { ReleaseMarkdown.spans(text) }
    val c = MaterialTheme.colorScheme
    return buildAnnotatedString {
        spans.forEach { span ->
            withStyle(SpanStyle(fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) c.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Unspecified)) {
                if (span.url != null) withLink(LinkAnnotation.Url(span.url,
                    TextLinkStyles(SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline)))) { append(span.text) }
                else append(span.text)
            }
        }
    }
}

@Composable
internal fun ReleaseNotes(notes: String) {
    val blocks = remember(notes) { ReleaseMarkdown.blocks(notes) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> when (block) {
            is MarkdownBlock.Line -> Text(inlineMarkdown(block.text),
                style = when (block.heading) {
                    1 -> MaterialTheme.typography.titleLarge
                    2 -> MaterialTheme.typography.titleMedium
                    in 3..6 -> MaterialTheme.typography.titleSmall
                    else -> MaterialTheme.typography.bodyMedium
                }, fontWeight = if (block.heading > 0) FontWeight.SemiBold else null,
                modifier = if (block.quote) Modifier.padding(start = 16.dp) else Modifier,
                color = if (block.quote) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            is MarkdownBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(block.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp))
            }
            is MarkdownBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                block.rows.forEachIndexed { index, row ->
                    Row {
                        row.forEach { cell ->
                            Text(inlineMarkdown(cell), style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (index == 0) FontWeight.Bold else null,
                                modifier = Modifier.width(180.dp).padding(8.dp))
                        }
                    }
                    HorizontalDivider()
                }
            }
            MarkdownBlock.Divider -> HorizontalDivider()
        } }
    }
}
