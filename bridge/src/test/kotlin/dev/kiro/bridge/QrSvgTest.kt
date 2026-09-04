package dev.kiro.bridge

import dev.kiro.core.auth.PairingPayload
import io.nayuki.qrcodegen.QrCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QrSvgTest {

    /** Pulls the module coordinates back out of the path's `M x yh1v1h-1z` runs. */
    private fun modulesIn(svg: String): Set<Pair<Int, Int>> =
        Regex("""M(\d+) (\d+)h1v1h-1z""").findAll(svg)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toSet()

    /**
     * The strongest statement available without a camera, and the same round trip
     * `TerminalQrTest` uses: every dark module survives the trip into geometry, in the
     * right place, and no light one is invented.
     */
    @Test
    fun `a rendered code reads back as the module grid it was drawn from`() {
        val qr = QrCode.encodeText("kiro svg round trip", QrCode.Ecc.MEDIUM)
        val svg = QrSvg.render(qr.size) { x, y -> qr.getModule(x, y) }

        val drawn = modulesIn(svg)
        val expected = buildSet {
            for (y in 0 until qr.size) {
                for (x in 0 until qr.size) {
                    if (qr.getModule(x, y)) add(x + QrSvg.QUIET_ZONE to y + QrSvg.QUIET_ZONE)
                }
            }
        }
        assertEquals(expected, drawn, "the SVG is not the code it was asked to draw")
    }

    /** Scanners find a code by its margin; without one there is nothing to lock onto. */
    @Test
    fun `the quiet zone is four clear modules on every side`() {
        val svg = QrSvg.render(5) { _, _ -> true }
        val drawn = modulesIn(svg)

        assertEquals(5 * 5, drawn.size)
        assertTrue(drawn.all { (x, y) -> x in 4..8 && y in 4..8 }, "something was drawn in the margin")
        assertTrue("""viewBox="0 0 13 13"""" in svg, svg.lines().first { "viewBox" in it })
    }

    /**
     * `TerminalQr` records the same finding for the terminal: ML Kit does not reliably
     * read inverted QR codes. A code that flips under `prefers-color-scheme: dark`
     * simply never scans, with nothing on screen to explain why — so the colours are
     * hard-coded rather than themed.
     */
    @Test
    fun `the code is black on white regardless of colour scheme`() {
        val svg = QrSvg.render(5) { x, y -> (x + y) % 2 == 0 }

        assertTrue("""fill="#ffffff"""" in svg, "the background is not white")
        assertTrue("""fill="#000000"""" in svg, "the modules are not black")
        assertTrue("currentColor" !in svg && "prefers-color-scheme" !in svg, "the QR is themeable: $svg")
    }

    /**
     * One path rather than a rect per module. At a rect each this is ~25KB of markup
     * on a page that re-fetches itself every 30 seconds.
     */
    @Test
    fun `a real payload stays small enough to inline`() {
        val payload = PairingPayload.encode("wss://bridge.example.com/acp", "8DD6YW6X")
        val svg = assertNotNull(QrSvg.forText(payload))

        assertTrue(svg.length < 20_000, "the inline SVG grew to ${svg.length} bytes")
        assertEquals(1, Regex("<path").findAll(svg).count(), "one path, not one per module")
    }

    /**
     * The payload becomes geometry and never markup, so nothing in an
     * operator-supplied `--public-url` can escape into the page.
     */
    @Test
    fun `the payload text never appears in the document`() {
        val svg = assertNotNull(QrSvg.forText(PairingPayload.encode("wss://h.example.com/acp", "8DD6YW6X")))

        assertTrue("8DD6YW6X" !in svg, "the code was written into the markup")
        assertTrue("h.example.com" !in svg, "the address was written into the markup")
    }

    /** Nothing to draw is a valid answer, not a crash. */
    @Test
    fun `text too long to encode yields no svg rather than an exception`() {
        assertEquals(null, QrSvg.forText("x".repeat(10_000)))
    }
}
