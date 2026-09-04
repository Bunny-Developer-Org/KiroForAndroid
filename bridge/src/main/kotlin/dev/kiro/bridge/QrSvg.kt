package dev.kiro.bridge

import io.nayuki.qrcodegen.QrCode

/**
 * Draws a QR code as inline SVG, for the `/qr` page.
 *
 * Split the same way [TerminalQr] is, and for the same reason: [forText] is the
 * only function here that names the QR library, and [render] -- where any bug of
 * consequence would live -- is pure and takes a module lookup, so it can be tested
 * without encoding anything.
 *
 * Inline rather than a separate image endpoint. An `<img src>` would be a second
 * route that mints or looks up a pairing code, reachable by a browser prefetch and
 * cacheable by anything in between; geometry embedded in a `no-store` document is
 * neither. It also means the payload text never appears in the markup at all, so
 * nothing in an operator-supplied URL can escape into HTML.
 */
internal object QrSvg {

    /** The spec's minimum margin. Scanners use it to find the code's edges. */
    const val QUIET_ZONE: Int = 4

    /**
     * @return the SVG document, or null when the text will not encode.
     */
    fun forText(text: String): String? {
        val qr = runCatching { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }.getOrNull() ?: return null
        return render(qr.size) { x, y -> qr.getModule(x, y) }
    }

    /**
     * @param size the QR's width in modules, excluding the quiet zone.
     * @param dark true when the module at (x, y) is a dark one.
     */
    fun render(size: Int, quietZone: Int = QUIET_ZONE, dark: (Int, Int) -> Boolean): String {
        val width = size + quietZone * 2

        // One path for the whole code, not one <rect> per module: a realistic payload
        // is a 33x33 code with several hundred dark modules, which is ~25 KB of
        // markup as rects against ~8 KB as a single path -- on a page that is
        // re-fetched every 30 seconds.
        val path = buildString {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (dark(x, y)) append("M${x + quietZone} ${y + quietZone}h1v1h-1z")
                }
            }
        }

        // Black on white in **both** colour schemes, deliberately. TerminalQr records
        // the same finding: ML Kit does not reliably read inverted QR codes, and a
        // code that inverts under `prefers-color-scheme: dark` is that bug wearing a
        // stylesheet -- it simply never scans, with nothing on screen to explain why.
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $width $width"
                 shape-rendering="crispEdges" role="img" aria-label="Pairing QR code">
              <rect width="$width" height="$width" fill="#ffffff"/>
              <path d="$path" fill="#000000"/>
            </svg>
        """.trimIndent()
    }
}
