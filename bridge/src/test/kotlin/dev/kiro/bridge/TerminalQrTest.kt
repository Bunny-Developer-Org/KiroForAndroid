package dev.kiro.bridge

import dev.kiro.core.auth.PairingPayload
import io.nayuki.qrcodegen.QrCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalQrTest {

    private fun lines(rendered: String): List<String> =
        rendered.lines().map { it.removePrefix("\u001B[97;40m").removeSuffix("\u001B[0m") }

    /**
     * The single thing most likely to be silently backwards, and backwards means
     * *unscannable*, not merely ugly: we ink the light modules, so that on a black
     * background the drawing has the polarity a scanner expects.
     */
    @Test
    fun `light modules are inked and dark modules are left blank`() {
        val allLight = TerminalQr.render(2, color = false, quietZone = 0) { _, _ -> false }
        val allDark = TerminalQr.render(2, color = false, quietZone = 0) { _, _ -> true }

        assertEquals("██", allLight)
        assertEquals("  ", allDark)
    }

    /**
     * The strongest statement available without a camera: every module survives the
     * trip through the glyphs, in the right place and the right way round. If
     * polarity, row pairing or the quiet-zone offset is wrong, this fails.
     */
    @Test
    fun `a rendered code reads back as the module grid it was drawn from`() {
        val qr = QrCode.encodeText("kiro pairing round trip", QrCode.Ecc.MEDIUM)
        val quietZone = TerminalQr.QUIET_ZONE
        val rendered = assertNotNull(TerminalQr.render(qr.size, color = false) { x, y -> qr.getModule(x, y) })

        val rows = lines(rendered)
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                val line = rows[(y + quietZone) / 2]
                val glyph = line[x + quietZone]
                val isTopHalf = (y + quietZone) % 2 == 0
                // A cell is dark in its top half for ' ' and '▄', in its bottom half
                // for ' ' and '▀' -- the inverse of the glyph table, since we ink light.
                val dark = if (isTopHalf) glyph == ' ' || glyph == '▄' else glyph == ' ' || glyph == '▀'
                assertEquals(qr.getModule(x, y), dark, "module ($x, $y) came back inverted or displaced")
            }
        }
    }

    /** Scanners find the code by its margin. Four modules is the spec's minimum. */
    @Test
    fun `the quiet zone is four light modules on every side`() {
        val rendered = assertNotNull(TerminalQr.render(5, color = false) { _, _ -> true })
        val rows = lines(rendered)

        // 5 + 8 = 13 rows -> the top two lines are wholly margin, as is the last.
        assertTrue(rows[0].all { it == '█' }, "top margin was not clear: ${rows[0]}")
        assertTrue(rows[1].all { it == '█' }, "top margin was not clear: ${rows[1]}")
        rows.forEach { line ->
            assertEquals("████", line.take(4), "left margin was not clear")
            assertEquals("████", line.takeLast(4), "right margin was not clear")
        }
    }

    /**
     * A QR's width is always odd, so the bottom half of the last line has no module
     * row behind it. It is the continuation of the quiet zone and must be drawn
     * light; drawn dark it halves the bottom margin and scanners lose the edge.
     */
    @Test
    fun `an odd module count still closes the bottom margin`() {
        val rendered = assertNotNull(TerminalQr.render(5, color = false) { _, _ -> true })

        assertTrue(lines(rendered).last().all { it == '█' }, "the bottom margin was cut in half")
    }

    /** A ragged block is not a QR code. */
    @Test
    fun `every rendered line is the same width`() {
        val qr = QrCode.encodeText(PairingPayload.encode("wss://bridge.example.com/acp", "8DD6YW6X"), QrCode.Ecc.MEDIUM)
        val rendered = assertNotNull(TerminalQr.render(qr.size, color = false) { x, y -> qr.getModule(x, y) })

        assertEquals(1, lines(rendered).map { it.length }.distinct().size)
    }

    /** Pins the size a realistic pairing payload actually produces. */
    @Test
    fun `a real pairing payload fits a QR small enough to draw in a terminal`() {
        val text = PairingPayload.encode("wss://bridge.example.com/acp", "8DD6YW6X")
        val rendered = assertNotNull(TerminalQr.forText(text, color = false))

        val width = lines(rendered).first().length
        assertTrue(width <= TerminalQr.MAX_COLUMNS, "a normal payload should fit a terminal, got $width columns")
    }

    /**
     * `--public-url` is operator-supplied and unbounded. A QR wider than the
     * terminal wraps, and a wrapped QR is unreadable -- better to print none and
     * let the banner fall back to its plain text.
     */
    @Test
    fun `an address too wide for a terminal yields no QR rather than a broken one`() {
        assertNull(TerminalQr.render(TerminalQr.MAX_COLUMNS + 1, color = false) { _, _ -> true })
    }

    /** https://no-color.org — respected, at the documented cost of dark-terminal-only output. */
    @Test
    fun `NO_COLOR suppresses the escape sequences`() {
        assertTrue(TerminalQr.colorEnabled(null))
        assertTrue(TerminalQr.colorEnabled(""))
        assertTrue(!TerminalQr.colorEnabled("1"))

        val plain = assertNotNull(TerminalQr.render(2, color = false, quietZone = 0) { _, _ -> false })
        assertTrue('\u001B' !in plain)
    }
}
