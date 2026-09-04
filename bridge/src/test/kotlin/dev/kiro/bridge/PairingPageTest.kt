package dev.kiro.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingPageTest {

    private fun live(secondsLeft: Long = 24, rotation: Int = 7, email: String = "alex@example.com") =
        PairingPage.live(URL, "8DD6YW6X", secondsLeft, rotation, email)

    private fun refreshSeconds(page: String): Long? =
        Regex("""http-equiv="refresh" content="(\d+)"""").find(page)?.groupValues?.get(1)?.toLong()

    /**
     * The page and the terminal banner are two faces of one thing, and a user may see
     * both. Lifting the labels rather than reinventing them is what stops them
     * drifting into "Address"/"Bridge URL".
     */
    @Test
    fun `the page uses the same labels as the terminal banner`() {
        val banner = pairingBanner(BridgeConfig(publicUrl = URL), "8DD6YW6X", color = false)
        val page = live()

        listOf("Address", "Code").forEach {
            assertTrue(it in banner, "the banner stopped using the label '$it'")
            assertTrue(">$it<" in page, "the page stopped using the label '$it'")
        }
    }

    /** Landing a second late means the picture on screen is never one the bridge left behind. */
    @Test
    fun `the page refreshes itself just after the code is due to change`() {
        assertEquals(25, refreshSeconds(live(secondsLeft = 24)))
        assertEquals(1, refreshSeconds(live(secondsLeft = 0)))
    }

    /**
     * If a stopped page still refreshed, it would mint again the moment the budget
     * let it, and the bound would do precisely nothing.
     */
    @Test
    fun `the stopped page has no refresh, no QR and no code`() {
        val page = PairingPage.stopped(rotations = 20)

        assertEquals(null, refreshSeconds(page), "the stopped page refreshes itself")
        assertTrue("<svg" !in page, "the stopped page still draws a QR")
        assertTrue("8DD6YW6X" !in page)
        assertTrue("kiro-bridge pair" in page, "it should still name the way out")
    }

    @Test
    fun `the paired page has no refresh and no code`() {
        val page = PairingPage.paired("Pixel 8a")

        assertEquals(null, refreshSeconds(page))
        assertTrue("<svg" !in page)
        assertTrue("Pixel 8a" in page)
    }

    /**
     * "A device paired", not "your phone paired": if somebody ran `kiro-bridge pair`
     * in the same window this names the wrong device, and the weaker claim reads as
     * information rather than as a lie.
     */
    @Test
    fun `the paired page does not claim the device is yours`() {
        val page = PairingPage.paired("Pixel 8a")

        assertTrue("A device paired" in page, page)
        assertTrue("Your phone" !in page)
    }

    /** The signed-in identity is rendered, so it is escaped. */
    @Test
    fun `the email is html escaped`() {
        val page = live(email = """evil<script>alert(1)</script>@x""")

        assertTrue("<script>" !in page, "an identity escaped into markup")
        assertTrue("&lt;script&gt;" in page)
    }

    /** `--public-url` is operator-supplied and lands on the page verbatim. */
    @Test
    fun `the advertised address is html escaped`() {
        val page = PairingPage.live("""wss://h/"><b>x""", "8DD6YW6X", 10, 1, "a@b.c")

        assertTrue("<b>x" !in page, "an address escaped into markup")
    }

    /**
     * Page and terminal must say identical words about an identical misconfiguration,
     * so the advisory is reused rather than rewritten.
     */
    @Test
    fun `a bridge with no advertisable address shows the advisory instead of a QR`() {
        val config = BridgeConfig(bindAddress = "0.0.0.0", tlsCertificate = null)
        val advisory = pairingAdvisory(config)!!
        val page = PairingPage.noAddress(advisory)

        assertTrue("--public-url" in page)
        assertTrue("<svg" !in page, "a QR was drawn for an address that cannot work")
    }

    /**
     * A countdown alone is a half-truth: it says when the code changes but not that
     * the one you are looking at keeps working for a moment after it does.
     */
    @Test
    fun `the copy states both the rotation and the grace`() {
        val page = live()

        assertTrue("${QrPageBudget.ROTATE_SECONDS}s" in page, "the page never says how often it rotates")
        assertTrue("${PairingService.SUPERSEDED_GRACE_SECONDS}s more" in page, "the page never mentions the grace")
    }

    /** You cannot scan your own screen, and the page is the only place to say so. */
    @Test
    fun `the phone-opened case is answered on the page itself`() {
        val page = live()

        assertTrue("cannot scan your own screen" in page, page)
        assertTrue("manual entry" in page)
    }

    private companion object {
        const val URL = "wss://bridge.example.com/acp"
    }
}
