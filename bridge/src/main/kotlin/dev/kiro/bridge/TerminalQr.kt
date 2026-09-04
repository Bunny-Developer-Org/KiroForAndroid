package dev.kiro.bridge

import io.nayuki.qrcodegen.QrCode

/**
 * Draws a QR code with text, for a terminal.
 *
 * Split deliberately: [forText] is the only thing in the module that names the QR
 * library, and [render] — where every bug of consequence lives — is pure and
 * takes a module lookup, so it can be tested without encoding anything.
 *
 * Two rows of modules are packed into each line using half-block glyphs, because
 * a terminal cell is about twice as tall as it is wide and a QR drawn one module
 * per cell comes out stretched and unscannable.
 */
internal object TerminalQr {

    /** The spec's minimum margin. Scanners use it to find the code's edges. */
    const val QUIET_ZONE: Int = 4

    /** Wider than this and the QR wraps, which destroys it. 80 is the old floor. */
    const val MAX_COLUMNS: Int = 80

    private const val BOTH_LIGHT = '█' // full block
    private const val TOP_LIGHT = '▀' // upper half block
    private const val BOTTOM_LIGHT = '▄' // lower half block
    private const val BOTH_DARK = ' '

    /**
     * Bright white on black, per line.
     *
     * Without this the drawing is only correct on a *dark* terminal: we ink the
     * light modules, so a light-background terminal renders the code inverted, and
     * ML Kit — the decoder on the app side — does not reliably read inverted QR
     * codes. The failure mode is a code that simply never scans, with nothing on
     * screen to explain why, so ~9 bytes a line is a cheap fix. `journalctl`
     * passes the escapes through untouched.
     */
    private const val SGR_ON = "\u001B[97;40m"
    private const val SGR_OFF = "\u001B[0m"

    /**
     * @return the rendered code, or null when it cannot be drawn — too wide for a
     *   terminal, or too much text to encode. Both are caller-recoverable: the
     *   pairing banner prints its address and code as text regardless.
     */
    fun forText(text: String, color: Boolean): String? {
        val qr = runCatching { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }.getOrNull() ?: return null
        return render(qr.size, color) { x, y -> qr.getModule(x, y) }
    }

    /**
     * @param size the QR's width in modules, excluding the quiet zone.
     * @param dark true when the module at (x, y) is a dark one.
     */
    fun render(size: Int, color: Boolean, quietZone: Int = QUIET_ZONE, dark: (Int, Int) -> Boolean): String? {
        val width = size + quietZone * 2
        if (width > MAX_COLUMNS) return null

        // Anything outside the code proper is quiet zone, and the quiet zone is light.
        fun isDark(x: Int, y: Int): Boolean {
            val mx = x - quietZone
            val my = y - quietZone
            return mx in 0 until size && my in 0 until size && dark(mx, my)
        }

        return (0 until width step 2).joinToString("\n") { row ->
            val line = (0 until width).map { col ->
                val top = isDark(col, row)
                // `width` is always odd (size is odd, the margins are equal), so the
                // last pair has no bottom row. It is the continuation of the bottom
                // quiet zone, so it is light -- calling it dark would eat half the
                // bottom margin and leave scanners without an edge to find.
                val bottom = row + 1 < width && isDark(col, row + 1)
                glyph(top, bottom)
            }.joinToString("")
            if (color) SGR_ON + line + SGR_OFF else line
        }
    }

    /** We ink the *light* modules; see [SGR_ON] for why that is the right way round. */
    private fun glyph(topDark: Boolean, bottomDark: Boolean): Char = when {
        !topDark && !bottomDark -> BOTH_LIGHT
        !topDark -> TOP_LIGHT
        !bottomDark -> BOTTOM_LIGHT
        else -> BOTH_DARK
    }

    /**
     * Honours `NO_COLOR` (https://no-color.org), the de-facto opt-out.
     *
     * The uncoloured form is still drawn light-on-dark, so it only scans on a dark
     * terminal — which is the trade a user makes by asking for no escapes.
     */
    fun colorEnabled(noColor: String?): Boolean = noColor.isNullOrEmpty()
}
