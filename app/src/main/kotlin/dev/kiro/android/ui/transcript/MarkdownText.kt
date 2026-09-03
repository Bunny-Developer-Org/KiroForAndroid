package dev.kiro.android.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiro.android.ui.common.HairlineSurface
import dev.kiro.android.ui.common.InstrumentHeader
import dev.kiro.android.ui.theme.KiroColors
import dev.kiro.android.ui.theme.KiroTheme

/**
 * Renders an agent message as Markdown.
 *
 * Set [streaming] while the text is still growing; see [MarkdownParser] for what
 * that licenses and why it is not the default.
 *
 * The parse is `remember`ed on the text, so the work per coalescing tick is one
 * linear scan plus one [AnnotatedString] build per changed block — not a tree of
 * composables rebuilt per token, which is the shape ADR-003 §3 rules out. A
 * paragraph is one [Text] node whether it is styled or not, so styling costs
 * layout nothing.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val blocks = remember(text, streaming) { MarkdownParser.parse(text, streaming) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block -> MarkdownBlockView(block) }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    val colors = KiroTheme.colors
    when (block) {
        is MarkdownBlock.Paragraph -> Text(
            block.spans.annotated(colors),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
        )

        is MarkdownBlock.Heading -> Text(
            block.spans.annotated(colors),
            modifier = Modifier.padding(top = 6.dp),
            style = headingStyle(block.level),
            color = colors.textStrong,
        )

        is MarkdownBlock.ListItem -> ListItemView(block, colors)

        is MarkdownBlock.CodeBlock -> CodeBlockView(block, colors)

        // A rule earns its own case rather than rendering as literal `---`,
        // because a section break is the one piece of structure that is pure
        // punctuation in the source and pure layout on screen.
        MarkdownBlock.Rule -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(1.dp)
                .background(colors.border),
        )
    }
}

@Composable
private fun ListItemView(block: MarkdownBlock.ListItem, colors: KiroColors) {
    Row(Modifier.fillMaxWidth().padding(start = (block.depth * NESTED_LIST_INDENT_DP).dp)) {
        Text(
            block.marker,
            modifier = Modifier.widthIn(min = 22.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        Text(
            block.spans.annotated(colors),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
        )
    }
}

/**
 * Long lines scroll rather than wrap — a wrapped shell command or signature is
 * misleading in a way a wrapped sentence is not. This is the minimum F-17 will
 * build on; language detection and copy belong to that item.
 */
@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock, colors: KiroColors) {
    HairlineSurface(
        Modifier.fillMaxWidth(),
        background = colors.bgElevated,
        shape = MaterialTheme.shapes.small,
    ) {
        Column {
            block.language?.let { InstrumentHeader(it) }
            Text(
                block.code,
                modifier = Modifier
                    .padding(12.dp)
                    .horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    else -> MaterialTheme.typography.titleSmall
}

/**
 * Spans to one [AnnotatedString].
 *
 * `background` on the code span rather than a bordered box: an inline code run
 * has to sit on the text baseline, and any container that can hold a border
 * cannot.
 */
private fun List<MarkdownSpan>.annotated(colors: KiroColors): AnnotatedString = buildAnnotatedString {
    forEach { span ->
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.SemiBold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) colors.bgElevated else Color.Unspecified,
            color = if (span.code) colors.textStrong else Color.Unspecified,
        )
        val url = span.link
        if (url == null) {
            withStyle(style) { append(span.text) }
        } else {
            // LinkAnnotation, not a click handler: it is what makes the run
            // reachable by TalkBack as a link and openable through the app's
            // UriHandler without this file knowing about intents.
            withLink(LinkAnnotation.Url(url, TextLinkStyles(style.merge(linkStyle(colors))))) {
                append(span.text)
            }
        }
    }
}

private fun linkStyle(colors: KiroColors) =
    SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)

private const val NESTED_LIST_INDENT_DP = 16
