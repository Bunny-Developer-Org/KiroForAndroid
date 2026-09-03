package dev.kiro.android.ui.transcript

/** One run of characters sharing a style. Flags rather than a tree: styles compose. */
internal data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

internal sealed interface MarkdownBlock {
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock

    /** [marker] is what gets drawn — `•` or `3.` — not the source characters. */
    data class ListItem(val marker: String, val depth: Int, val spans: List<MarkdownSpan>) : MarkdownBlock

    /** An unclosed fence still produces one of these: CommonMark runs it to EOF, and so does a stream. */
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/**
 * Markdown, parsed for a **stream** rather than for a document.
 *
 * An agent answer arrives as `agent_message_chunk` notifications that
 * `TranscriptReducer` concatenates (`streamingText + update.text`) and
 * [TranscriptViewModel] hands to the UI once per `TICK_MILLIS`. This parser is
 * therefore called tens of times per message, always on a *prefix* of the final
 * text. Three consequences shape the whole file:
 *
 *  1. **Nothing here throws.** Every construct has a defined result when its
 *     terminator has not arrived yet, so a half-written link or a lone `**` is
 *     an ordinary input, not an edge case.
 *  2. **A delimiter that is still open styles what it has already opened.**
 *     In `streaming` mode a trailing `**` is swallowed and the text after it is
 *     bolded immediately, so the reader never watches literal `**nieoficjalny`
 *     sit on screen and then snap. Committed text is parsed with
 *     `streaming = false`, where an unterminated delimiter falls back to a
 *     literal — otherwise a finished message containing `use **kwargs` would be
 *     silently bolded to the end.
 *  3. **One linear pass, no backtracking, no tree.** ADR-003 §3 names
 *     "recomputing highlighting on a growing string per token" as the specific
 *     mistake to avoid; an O(n) scan producing an immutable value the UI can
 *     `remember` is what makes re-running it on every tick affordable.
 *
 * Scope is the syntax Kiro actually emits: ATX headings, bold, italic, inline
 * code, fenced code blocks, bullet and numbered lists, links, and thematic
 * breaks. **Tables and raw HTML are deliberately out of scope** — they pass
 * through as their literal source. Richer rendering is F-17's brief, not this
 * one's.
 */
internal object MarkdownParser {

    /**
     * [streaming] must be true only while [text] is still growing. It is what
     * licenses the optimistic reading of an unterminated delimiter, and it is
     * applied to the **last block only** — earlier blocks were closed by a
     * blank line or a new block and are therefore already final.
     */
    fun parse(text: String, streaming: Boolean = false): List<MarkdownBlock> {
        val scanner = BlockScanner()
        for (line in text.split('\n')) scanner.accept(line)
        val raw = scanner.finish()
        return raw.mapIndexed { index, block ->
            block.toBlock(optimistic = streaming && index == raw.lastIndex)
        }
    }
}

private const val FENCE_MIN = 3
private const val MAX_MARKER_INDENT = 3
private const val INDENT_PER_LEVEL = 2
private const val MAX_LIST_DEPTH = 4
private const val MAX_NESTING = 6
private const val BULLET_GLYPH = "•"
private const val ESCAPABLE = "\\`*_[]()#+-.!~>"

private val HEADING = Regex("""(#{1,6})(?:\s+(.*))?""")
private val BULLET = Regex("""([-*+])\s+(.*)""")
private val ORDERED = Regex("""(\d{1,9})[.)]\s+(.*)""")
private val RULE = Regex("""(?:-{3,}|\*{3,}|_{3,})\s*""")

private enum class RawKind { PARAGRAPH, HEADING, LIST_ITEM, CODE, RULE }

private data class RawBlock(
    val kind: RawKind,
    val text: String,
    val level: Int = 0,
    val marker: String = "",
    val depth: Int = 0,
    val language: String? = null,
)

private fun RawBlock.toBlock(optimistic: Boolean): MarkdownBlock = when (kind) {
    RawKind.PARAGRAPH -> MarkdownBlock.Paragraph(parseInline(text, optimistic))
    RawKind.HEADING -> MarkdownBlock.Heading(level, parseInline(text, optimistic))
    RawKind.LIST_ITEM -> MarkdownBlock.ListItem(marker, depth, parseInline(text, optimistic))
    RawKind.CODE -> MarkdownBlock.CodeBlock(language, text)
    RawKind.RULE -> MarkdownBlock.Rule
}

/**
 * Line-at-a-time block splitting.
 *
 * Written as an object with one `accept` per line rather than as a loop full of
 * `continue`s, because the fenced-code state has to survive between lines and
 * the alternative is a nest of flags inside the loop body.
 */
private class BlockScanner {

    private val blocks = mutableListOf<RawBlock>()
    private val paragraph = StringBuilder()
    private val code = StringBuilder()
    private var fence: String? = null
    private var language: String? = null

    fun accept(line: String) {
        val open = fence
        if (open != null) acceptCodeLine(open, line) else acceptTextLine(line)
    }

    fun finish(): List<RawBlock> {
        // An unterminated fence is the normal mid-stream state, not a syntax
        // error: emit what has arrived so far as code, exactly as CommonMark
        // treats a fence left open at end of document.
        if (fence != null) emitCode()
        flushParagraph()
        return blocks
    }

    private fun acceptCodeLine(open: String, line: String) {
        val trimmed = line.trim()
        val closes = trimmed.length >= open.length && trimmed.all { it == open[0] }
        if (closes) {
            emitCode()
        } else {
            if (code.isNotEmpty()) code.append('\n')
            code.append(line)
        }
    }

    private fun acceptTextLine(line: String) {
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) {
            flushParagraph()
            return
        }
        val body = line.substring(indent)
        val run = fenceRunOf(body)
        when {
            run != null -> openFence(run, body)
            RULE.matches(body) -> {
                flushParagraph()
                blocks += RawBlock(RawKind.RULE, "")
            }
            else -> acceptStructuredLine(body, indent)
        }
    }

    private fun acceptStructuredLine(body: String, indent: Int) {
        val heading = HEADING.matchEntire(body)?.takeIf { indent <= MAX_MARKER_INDENT }
        val bullet = BULLET.matchEntire(body)
        val ordered = ORDERED.matchEntire(body)
        when {
            heading != null -> {
                flushParagraph()
                blocks += RawBlock(
                    RawKind.HEADING,
                    heading.groupValues[2].trim(),
                    level = heading.groupValues[1].length,
                )
            }
            bullet != null -> emitItem(BULLET_GLYPH, indent, bullet.groupValues[2])
            ordered != null -> emitItem(ordered.groupValues[1] + ".", indent, ordered.groupValues[2])
            isItemContinuation(indent) -> appendToLastItem(body)
            else -> appendToParagraph(body)
        }
    }

    private fun emitItem(marker: String, indent: Int, text: String) {
        flushParagraph()
        blocks += RawBlock(
            RawKind.LIST_ITEM,
            text.trim(),
            marker = marker,
            depth = (indent / INDENT_PER_LEVEL).coerceAtMost(MAX_LIST_DEPTH),
        )
    }

    /** An indented line under a list item belongs to it, not to a paragraph of its own. */
    private fun isItemContinuation(indent: Int) =
        indent >= INDENT_PER_LEVEL && paragraph.isEmpty() && blocks.lastOrNull()?.kind == RawKind.LIST_ITEM

    private fun appendToLastItem(body: String) {
        val last = blocks.removeAt(blocks.lastIndex)
        blocks += last.copy(text = (last.text + " " + body).trim())
    }

    private fun appendToParagraph(body: String) {
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(body)
    }

    private fun openFence(run: String, body: String) {
        flushParagraph()
        fence = run
        language = body.substring(run.length).trim().ifEmpty { null }
        code.setLength(0)
    }

    private fun emitCode() {
        blocks += RawBlock(RawKind.CODE, code.toString(), language = language)
        fence = null
        language = null
        code.setLength(0)
    }

    private fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += RawBlock(RawKind.PARAGRAPH, paragraph.toString().trim())
        paragraph.setLength(0)
    }
}

private fun fenceRunOf(body: String): String? {
    val first = body.firstOrNull() ?: return null
    if (first != '`' && first != '~') return null
    val run = body.takeWhile { it == first }
    return run.takeIf { it.length >= FENCE_MIN }
}

private fun parseInline(text: String, optimistic: Boolean): List<MarkdownSpan> =
    InlineScanner(text, optimistic, depth = 0).scan()

/**
 * Inline spans.
 *
 * Every handler returns `true` when it consumed input and `false` to let the
 * character fall through as a literal, which is what keeps the "unterminated
 * construct is not an error" promise honest: the fallback path is the same code
 * that handles ordinary text.
 */
private class InlineScanner(
    private val text: String,
    private val optimistic: Boolean,
    private val depth: Int,
) {

    private val spans = mutableListOf<MarkdownSpan>()
    private val buffer = StringBuilder()
    private var index = 0

    fun scan(): List<MarkdownSpan> {
        while (index < text.length) step()
        flush()
        return spans
    }

    private fun step() {
        val c = text[index]
        val consumed = when (c) {
            '\\' -> escape()
            '`' -> codeSpan()
            '[' -> link()
            '*', '_' -> emphasis(c)
            else -> false
        }
        if (!consumed) {
            buffer.append(c)
            index++
        }
    }

    private fun escape(): Boolean {
        val next = text.getOrNull(index + 1) ?: return false
        if (next !in ESCAPABLE) return false
        buffer.append(next)
        index += 2
        return true
    }

    private fun codeSpan(): Boolean {
        val run = runLength('`')
        val close = findRun('`', run, index + run)
        if (close >= 0) {
            emitCode(text.substring(index + run, close))
            index = close + run
            return true
        }
        if (!optimistic) return false
        // Mid-stream: the opener has arrived, the closer has not. Style what is
        // there instead of showing the backticks and snapping a tick later.
        if (index + run < text.length) emitCode(text.substring(index + run))
        index = text.length
        return true
    }

    private fun emphasis(marker: Char): Boolean {
        if (depth >= MAX_NESTING) return false
        val run = runLength(marker)
        if (index + run >= text.length) {
            // A delimiter run sitting at the very end of the buffer: swallow it
            // while streaming so the asterisks are never drawn at all.
            if (!optimistic) return false
            index = text.length
            return true
        }
        if (!opens(marker, run)) return false
        val close = findCloser(marker, run, index + run)
        if (close >= 0) {
            emitStyled(text.substring(index + run, close), run, openEnded = false)
            index = close + run
            return true
        }
        if (!optimistic) return false
        emitStyled(text.substring(index + run), run, openEnded = true)
        index = text.length
        return true
    }

    private fun link(): Boolean {
        if (depth >= MAX_NESTING) return false
        val close = text.indexOf(']', index + 1)
        if (close < 0) return partialLink(index + 1, text.length, resumeAt = text.length)
        if (text.getOrNull(close + 1) != '(') return partialLink(index + 1, close, resumeAt = close + 1)
        val end = text.indexOf(')', close + 2)
        // The destination is still arriving. Hold back everything after the
        // label, or the half-typed URL is drawn and then deleted.
        if (end < 0) return partialLink(index + 1, close, resumeAt = text.length)
        emitLink(text.substring(index + 1, close), text.substring(close + 2, end).trim())
        index = end + 1
        return true
    }

    /**
     * A link whose destination has not arrived yet renders as its label alone —
     * no brackets, no half-typed URL. The label's position is then identical
     * before and after the URL lands, so completing the link recolours text
     * rather than reflowing it.
     */
    private fun partialLink(from: Int, to: Int, resumeAt: Int): Boolean {
        if (!optimistic) return false
        flush()
        spans += InlineScanner(text.substring(from, to), optimistic = true, depth = depth + 1).scan()
        index = resumeAt
        return true
    }

    private fun emitStyled(inner: String, run: Int, openEnded: Boolean) {
        flush()
        val bold = run >= 2
        val italic = run == 1 || run > 2
        InlineScanner(inner, openEnded, depth + 1).scan().forEach {
            spans += it.copy(bold = it.bold || bold, italic = it.italic || italic)
        }
    }

    private fun emitLink(label: String, url: String) {
        flush()
        InlineScanner(label.ifEmpty { url }, optimistic = false, depth = depth + 1)
            .scan()
            .forEach { spans += it.copy(link = url) }
    }

    private fun emitCode(content: String) {
        flush()
        spans += MarkdownSpan(content, code = true)
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        spans += MarkdownSpan(buffer.toString())
        buffer.setLength(0)
    }

    private fun runLength(c: Char): Int {
        var end = index
        while (end < text.length && text[end] == c) end++
        return end - index
    }

    /**
     * CommonMark's left-flanking rule, reduced to the two cases that actually
     * bite: `2 * 3 * 4` must not become italics, and `snake_case_name` must not
     * either.
     */
    private fun opens(marker: Char, run: Int): Boolean {
        val next = text.getOrNull(index + run) ?: return false
        if (next.isWhitespace()) return false
        return !(marker == '_' && text.getOrNull(index - 1)?.isLetterOrDigit() == true)
    }

    private fun findCloser(marker: Char, run: Int, from: Int): Int {
        var j = from
        while (j < text.length) {
            val end = if (text[j] == marker) runEndAt(j, marker) else -1
            if (end - j == run && closes(marker, j, end)) return j
            j = if (end > j) end else j + 1
        }
        return -1
    }

    private fun closes(marker: Char, start: Int, end: Int): Boolean {
        if (text[start - 1].isWhitespace()) return false
        return !(marker == '_' && text.getOrNull(end)?.isLetterOrDigit() == true)
    }

    private fun findRun(c: Char, len: Int, from: Int): Int {
        var j = from
        while (j < text.length) {
            val end = if (text[j] == c) runEndAt(j, c) else -1
            if (end - j == len) return j
            j = if (end > j) end else j + 1
        }
        return -1
    }

    private fun runEndAt(start: Int, c: Char): Int {
        var end = start
        while (end < text.length && text[end] == c) end++
        return end
    }
}
