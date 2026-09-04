package dev.kiro.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingBannerTest {

    /**
     * The fallback is not optional. A terminal that mangles the block glyphs, an
     * operator reading over someone's shoulder, `--no-qr`, an address too wide to
     * draw -- none of them may leave a person without a way to pair.
     */
    @Test
    fun `the address and code are printed as text whether or not a QR is drawn`() {
        val withQr = pairingBanner(loopback(printQr = true), "8DD6YW6X", color = false)
        val withoutQr = pairingBanner(loopback(printQr = false), "8DD6YW6X", color = false)

        listOf(withQr, withoutQr).forEach { banner ->
            assertTrue("ws://127.0.0.1:8765/acp" in banner, "the address went missing:\n$banner")
            assertTrue("8DD6YW6X" in banner, "the code went missing:\n$banner")
        }
        assertTrue('█' in withQr, "a QR was asked for and not drawn")
        assertTrue('█' !in withoutQr, "--no-qr still drew a QR")
    }

    /**
     * The single most likely way this feature gets deployed wrong: a tunnelled
     * bridge binds loopback, so without --public-url its QR sends the phone to
     * itself. The banner has to say so at the moment the operator is looking.
     */
    @Test
    fun `a loopback bridge with no public URL warns that the QR points at loopback`() {
        val advisory = assertNotNull(pairingAdvisory(loopback()))

        assertTrue("--public-url" in advisory, "the advisory should name the flag that fixes it")

        // The banner indents every advisory line, so compare line by line rather
        // than looking for the block verbatim.
        val banner = pairingBanner(loopback(), "8DD6YW6X", color = false).lines().map { it.trim() }
        advisory.lines().filter { it.isNotBlank() }.forEach { line ->
            assertTrue(line.trim() in banner, "the banner dropped an advisory line: $line")
        }
    }

    /** A bridge that has been told its address has nothing to be warned about. */
    @Test
    fun `a bridge with a public URL prints no advisory`() {
        val config = BridgeConfig(publicUrl = "wss://bridge.example.com/acp")

        assertNull(pairingAdvisory(config))
        assertTrue("wss://bridge.example.com/acp" in pairingBanner(config, "8DD6YW6X", color = false))
    }

    /** No single address exists to advertise, so there is nothing honest to put in a QR. */
    @Test
    fun `a wildcard bind draws no QR and says why`() {
        val config = BridgeConfig(bindAddress = "0.0.0.0", tlsCertificate = null)
        val banner = pairingBanner(config, "8DD6YW6X", color = false)

        assertTrue('█' !in banner, "a QR was drawn for an address that cannot work")
        assertTrue("--public-url" in banner)
        assertTrue("8DD6YW6X" in banner, "the code is still the way in")
    }

    /**
     * `tools/deploy/gcp/deploy.sh` reads this block out of the journal. Pinning the
     * text lines keeps that script's window honest -- and a QR must not push the
     * address and code so far apart that they get separated.
     */
    @Test
    fun `the text block stays compact enough to read out of a journal`() {
        val textLines = pairingBanner(loopback(printQr = false), "8DD6YW6X", color = false)
            .lines().filter { it.isNotBlank() }

        assertTrue(textLines.size <= MAX_TEXT_LINES, "the banner grew to ${textLines.size} lines:\n$textLines")
    }

    /** An API-key bridge is a different trust statement and settings must not blur it. */
    @Test
    fun `an api key bridge says so`() {
        val banner = pairingBanner(loopback().copy(apiKey = "k"), "8DD6YW6X", color = false)

        assertTrue("KIRO_API_KEY" in banner)
    }

    /** Running `pair` twice retires the first code; an operator should not learn that by scanning. */
    @Test
    fun `a footer is rendered when the caller supplies one`() {
        val banner = pairingBanner(
            url = "wss://h/acp",
            code = "8DD6YW6X",
            ttlSeconds = 300,
            advisory = null,
            footer = "This replaces any pairing code printed earlier.",
            printQr = false,
            color = false,
        )

        assertTrue("replaces any pairing code" in banner)
    }

    /** Five minutes, said in minutes, because that is how a person reads a deadline. */
    @Test
    fun `the time to live is shown in minutes`() {
        val banner = pairingBanner(loopback(printQr = false), "8DD6YW6X", color = false)

        assertEquals(300L, PairingService.CODE_TTL_SECONDS)
        assertTrue("valid for 5 minutes" in banner, banner)
    }

    private fun loopback(printQr: Boolean = true) = BridgeConfig(printQr = printQr)

    private companion object {
        const val MAX_TEXT_LINES = 20
    }
}
