package dev.kiro.android.ui.onboarding

import dev.kiro.core.auth.PairingPayload
import dev.kiro.core.auth.PairingPayload.DecodeResult
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanMessagesTest {

    /**
     * A camera pointed at the world meets far more non-payloads than payloads, so
     * this is the message users will actually see most often. It has to say what
     * they scanned was the wrong thing, not that something broke.
     */
    @Test
    fun `a QR that is not a pairing code says so, and points somewhere useful`() {
        val message = assertNotNull(scanErrorMessage(DecodeResult.NotAPairingCode))

        assertTrue("by hand" in message, "every scan failure must name manual entry: $message")
        assertTrue("kiro-bridge pair" in message, "it should say where a real code comes from: $message")
    }

    /**
     * The one case where the app genuinely cannot proceed on its own — so it is
     * also the one where a dead end would be easiest to write by accident.
     */
    @Test
    fun `a bridge newer than the app still offers a way in`() {
        val message = assertNotNull(scanErrorMessage(DecodeResult.UnsupportedVersion(99)))

        assertTrue("by hand" in message, "an unsupported version must not be a dead end: $message")
        assertTrue("still works" in message, "it should say plainly that manual entry is unaffected: $message")
    }

    /** The reason comes from the decoder and belongs in front of the user, not in a log. */
    @Test
    fun `a malformed payload explains what was wrong with it`() {
        val message = assertNotNull(scanErrorMessage(DecodeResult.Malformed("the address has no host")))

        assertTrue("the address has no host" in message, message)
        assertTrue("by hand" in message, message)
    }

    /** Nothing to say when nothing went wrong. */
    @Test
    fun `a successful decode produces no message at all`() {
        val ok = PairingPayload.decode(PairingPayload.encode("wss://h.example.com/acp", "8DD6YW6X"))

        assertNull(scanErrorMessage(ok))
    }
}
