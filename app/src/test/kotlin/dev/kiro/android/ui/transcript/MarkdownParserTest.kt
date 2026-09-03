package dev.kiro.android.ui.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cases that matter are the *partial* ones.
 *
 * `TranscriptReducer` appends every `agent_message_chunk` to one growing string
 * and `TranscriptViewModel` publishes it every `TICK_MILLIS`, so the parser is
 * run on a prefix of the final text far more often than on the final text
 * itself. The prefix sweeps below are therefore the real regression guard; the
 * happy-path assertions only pin down what the prefixes are converging on.
 */
class MarkdownParserTest {

    private fun plain(blocks: List<MarkdownBlock>): String = blocks.joinToString("\n") { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> block.spans.joinToString("") { it.text }
            is MarkdownBlock.Heading -> block.spans.joinToString("") { it.text }
            is MarkdownBlock.ListItem -> block.spans.joinToString("") { it.text }
            is MarkdownBlock.CodeBlock -> block.code
            MarkdownBlock.Rule -> ""
        }
    }

    private fun spansOf(blocks: List<MarkdownBlock>): List<MarkdownSpan> = blocks.flatMap {
        when (it) {
            is MarkdownBlock.Paragraph -> it.spans
            is MarkdownBlock.Heading -> it.spans
            is MarkdownBlock.ListItem -> it.spans
            else -> emptyList()
        }
    }

    // -- the shapes the agent actually emits ---------------------------------

    @Test
    fun `an ATX heading becomes a heading of the right level`() {
        val blocks = MarkdownParser.parse("## KiroForAndroid — co robi ten kod")
        val heading = blocks.single() as MarkdownBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("KiroForAndroid — co robi ten kod", heading.spans.single().text)
    }

    @Test
    fun `bold italic and inline code carry their flags and drop their delimiters`() {
        val spans = spansOf(MarkdownParser.parse("a **b** c *d* e `f` g"))
        assertEquals("b", spans.single { it.bold }.text)
        assertEquals("d", spans.single { it.italic }.text)
        assertEquals("f", spans.single { it.code }.text)
        assertFalse(plain(MarkdownParser.parse("a **b** c *d* e `f` g")).contains('*'))
    }

    @Test
    fun `a link keeps its label and hides its destination`() {
        val spans = spansOf(MarkdownParser.parse("see [the docs](https://kiro.dev) now"))
        val link = spans.single { it.link != null }
        assertEquals("the docs", link.text)
        assertEquals("https://kiro.dev", link.link)
        assertFalse(spans.any { it.text.contains("https://kiro.dev") })
    }

    @Test
    fun `bullet and numbered lists become items with rendered markers`() {
        val blocks = MarkdownParser.parse("- one\n- two\n\n1. first\n2. second")
        val items = blocks.filterIsInstance<MarkdownBlock.ListItem>()
        assertEquals(listOf("•", "•", "1.", "2."), items.map { it.marker })
        assertEquals(listOf("one", "two", "first", "second"), items.map { it.spans.single().text })
    }

    @Test
    fun `a nested item is indented rather than flattened`() {
        val items = MarkdownParser.parse("- one\n  - nested").filterIsInstance<MarkdownBlock.ListItem>()
        assertEquals(listOf(0, 1), items.map { it.depth })
    }

    @Test
    fun `a fenced block keeps its language and its interior verbatim`() {
        val block = MarkdownParser
            .parse("```kotlin\nval a = **not bold**\n```")
            .single() as MarkdownBlock.CodeBlock
        assertEquals("kotlin", block.language)
        assertEquals("val a = **not bold**", block.code)
    }

    // -- partial input: the streaming contract -------------------------------

    @Test
    fun `an unclosed fence still renders as code rather than as source`() {
        val block = MarkdownParser
            .parse("```kotlin\nfun main() {", streaming = true)
            .single() as MarkdownBlock.CodeBlock
        assertEquals("fun main() {", block.code)
    }

    @Test
    fun `an open bold delimiter styles the text it has already opened`() {
        val spans = spansOf(MarkdownParser.parse("this is **nieoficjalny", streaming = true))
        assertEquals("nieoficjalny", spans.single { it.bold }.text)
        assertFalse(spans.any { it.text.contains('*') })
    }

    @Test
    fun `a delimiter run at the very end of the buffer is never drawn`() {
        listOf("word *", "word **", "word `").forEach { prefix ->
            assertFalse(
                plain(MarkdownParser.parse(prefix, streaming = true)).trimEnd().endsWith("*"),
                "a trailing delimiter leaked into the rendered text of \"$prefix\"",
            )
        }
        assertEquals("word", plain(MarkdownParser.parse("word **", streaming = true)).trim())
    }

    @Test
    fun `a half-written link shows its label without brackets or a partial URL`() {
        listOf("see [the do", "see [the docs", "see [the docs]", "see [the docs](htt")
            .forEach { prefix ->
                val rendered = plain(MarkdownParser.parse(prefix, streaming = true))
                assertFalse(rendered.contains('['), "brackets leaked for \"$prefix\": $rendered")
                assertFalse(rendered.contains("htt"), "a partial URL leaked for \"$prefix\": $rendered")
            }
    }

    @Test
    fun `committed text falls back to a literal so an unpaired delimiter is not swallowed`() {
        // The counter-case to the optimistic reading: once the turn has ended
        // this text is final, and `**kwargs` is punctuation the author meant.
        val rendered = plain(MarkdownParser.parse("pass **kwargs through", streaming = false))
        assertEquals("pass **kwargs through", rendered)
        assertTrue(spansOf(MarkdownParser.parse("pass **kwargs through")).none { it.bold })
    }

    @Test
    fun `every prefix of a realistic answer parses without throwing`() {
        for (end in 0..SAMPLE.length) {
            val prefix = SAMPLE.substring(0, end)
            MarkdownParser.parse(prefix, streaming = true)
            MarkdownParser.parse(prefix, streaming = false)
        }
    }

    @Test
    fun `no prefix of a streaming answer ever shows an emphasis delimiter`() {
        // This is the anti-flicker invariant stated as a property: if the
        // asterisks are never rendered, there is no frame in which they are
        // there and a later frame in which they are not.
        for (end in 0..SAMPLE.length) {
            val rendered = plain(MarkdownParser.parse(SAMPLE.substring(0, end), streaming = true))
            val outsideCode = MarkdownParser.parse(SAMPLE.substring(0, end), streaming = true)
                .none { it is MarkdownBlock.CodeBlock && it.code.contains('*') }
            if (outsideCode) {
                assertFalse(rendered.contains("**"), "prefix of length $end rendered as: $rendered")
            }
        }
    }

    @Test
    fun `the rendered text of a prefix only ever grows`() {
        // Text may gain characters as chunks land; it must not lose them, which
        // is what a delimiter appearing and then being reinterpreted looks like.
        var previous = 0
        for (end in 0..SAMPLE.length) {
            val length = plain(MarkdownParser.parse(SAMPLE.substring(0, end), streaming = true))
                .filterNot { it.isWhitespace() }
                .length
            assertTrue(
                length >= previous - SHRINK_TOLERANCE,
                "rendered text shrank by ${previous - length} at prefix length $end",
            )
            previous = length
        }
    }

    // -- things that must NOT be markdown ------------------------------------

    @Test
    fun `arithmetic is not italics and snake case is not emphasis`() {
        assertEquals("2 * 3 * 4", plain(MarkdownParser.parse("2 * 3 * 4")))
        assertEquals("some_long_name", plain(MarkdownParser.parse("some_long_name")))
        assertTrue(spansOf(MarkdownParser.parse("some_long_name")).none { it.italic })
    }

    @Test
    fun `tables and raw HTML are out of scope and pass through as text`() {
        val table = "| a | b |\n| - | - |"
        assertTrue(plain(MarkdownParser.parse(table)).contains("| a | b |"))
        assertTrue(plain(MarkdownParser.parse("<b>hi</b>")).contains("<b>hi</b>"))
    }

    @Test
    fun `an escaped delimiter is a literal`() {
        assertEquals("a *b* c", plain(MarkdownParser.parse("""a \*b\* c""")))
    }

    // -- cheap enough to run on every tick -----------------------------------

    @Test
    fun `a long message parses in linear time without recursing to a stack overflow`() {
        val long = SAMPLE.repeat(200)
        val started = System.nanoTime()
        repeat(TICKS_PER_SECOND) { MarkdownParser.parse(long, streaming = true) }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(
            elapsedMillis < ONE_SECOND_MILLIS,
            "a second's worth of ticks over a ${long.length}-char message took ${elapsedMillis}ms",
        )
        // Pathological delimiter soup must terminate too -- MAX_NESTING is what
        // stops the recursive span parse from unwinding the stack.
        MarkdownParser.parse("*".repeat(4000) + "x", streaming = true)
        MarkdownParser.parse("[".repeat(4000) + "x", streaming = true)
    }

    private companion object {
        const val SHRINK_TOLERANCE = 2
        const val TICKS_PER_SECOND = 13
        const val ONE_SECOND_MILLIS = 1000

        val SAMPLE = """
            ## KiroForAndroid — co robi ten kod

            To jest **nieoficjalny klient** dla Kiro, napisany w *Kotlinie*.
            Wejście do aplikacji to `MainActivity`, a most opisuje
            [ADR-005](https://example.invalid/adr-005).

            ---

            Warstwy:

            - `core/` — protokół ACP, bez `android.*`
            - `app/` — Compose UI
              i jego nawigacja
            1. sparuj most
            2. zaloguj się

            ```kotlin
            fun main() {
                println("hello **world**")
            }
            ```

            Koniec.
        """.trimIndent()
    }
}
