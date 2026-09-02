package dev.kiro.bridge

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AUTHENTICATION §4 lists these as mandatory rather than desirable, so they are
 * tested rather than assumed.
 */
class PairingServiceTest {

    private var now = Instant.parse("2026-09-02T12:00:00Z")
    private val stateDir = Files.createTempDirectory("pairing-test").toFile()
    private val service = PairingService(stateDir, clock = { now })

    @Test
    fun `a code works once and then never again`() {
        val code = service.issueCode()

        val first = assertIs<PairingService.PairResult.Paired>(service.redeem(code, "Pixel", "1.2.3.4"))
        assertTrue(service.isAuthorised(first.token))

        assertIs<PairingService.PairResult.BadCode>(service.redeem(code, "Attacker", "5.6.7.8"))
    }

    @Test
    fun `an expired code is refused and is not left redeemable`() {
        val code = service.issueCode()
        now = now.plus(Duration.ofSeconds(PairingService.CODE_TTL_SECONDS + 1))

        assertIs<PairingService.PairResult.Expired>(service.redeem(code, "Pixel", "1.2.3.4"))
        // Expiry must also consume it, or a clock adjustment reopens the window.
        assertIs<PairingService.PairResult.BadCode>(service.redeem(code, "Pixel", "1.2.3.4"))
    }

    @Test
    fun `guessing is rate limited per address`() {
        repeat(PairingService.MAX_ATTEMPTS_PER_WINDOW) {
            assertIs<PairingService.PairResult.BadCode>(service.redeem("WRONGONE", "x", "9.9.9.9"))
        }
        val limited = assertIs<PairingService.PairResult.RateLimited>(
            service.redeem("WRONGONE", "x", "9.9.9.9"),
        )
        assertTrue(limited.retryAfterSeconds > 0)

        // Another device is not punished for the first one's guessing.
        val code = service.issueCode()
        assertIs<PairingService.PairResult.Paired>(service.redeem(code, "Pixel", "1.1.1.1"))
    }

    @Test
    fun `only a hash of the token reaches disk`() {
        val code = service.issueCode()
        val token = assertIs<PairingService.PairResult.Paired>(
            service.redeem(code, "Pixel", "1.2.3.4"),
        ).token

        val onDisk = stateDir.resolve("devices.txt").readText()
        assertFalse(
            onDisk.contains(token),
            "a compromised bridge host must not yield a working credential for every paired phone",
        )
        assertTrue(onDisk.contains("Pixel"))
    }

    @Test
    fun `revocation takes effect immediately and survives a restart`() {
        val code = service.issueCode()
        val token = assertIs<PairingService.PairResult.Paired>(
            service.redeem(code, "Pixel", "1.2.3.4"),
        ).token

        val device = service.listDevices().single()
        assertTrue(service.revoke(device.tokenHash))
        assertFalse(service.isAuthorised(token))

        val reloaded = PairingService(stateDir, clock = { now })
        assertFalse(reloaded.isAuthorised(token))
        assertEquals(0, reloaded.listDevices().size)
    }

    @Test
    fun `codes avoid characters that are misread when typed by hand`() {
        repeat(20) {
            val code = service.issueCode()
            assertEquals(PairingService.CODE_LENGTH, code.length)
            assertTrue(code.none { it in "IO01" }, "ambiguous character in $code")
        }
    }

    @Test
    fun `an unpaired client is not authorised`() {
        assertFalse(service.isAuthorised(null))
        assertFalse(service.isAuthorised(""))
        assertFalse(service.isAuthorised("made-up-token"))
    }
}
