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
        val code = service.issueCode(PairingService.CodeSource.TERMINAL)

        val first = assertIs<PairingService.PairResult.Paired>(service.redeem(code, "Pixel", "1.2.3.4"))
        assertTrue(service.isAuthorised(first.token))

        assertIs<PairingService.PairResult.BadCode>(service.redeem(code, "Attacker", "5.6.7.8"))
    }

    @Test
    fun `an expired code is refused and is not left redeemable`() {
        val code = service.issueCode(PairingService.CodeSource.TERMINAL)
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
        val code = service.issueCode(PairingService.CodeSource.TERMINAL)
        assertIs<PairingService.PairResult.Paired>(service.redeem(code, "Pixel", "1.1.1.1"))
    }

    @Test
    fun `only a hash of the token reaches disk`() {
        val code = service.issueCode(PairingService.CodeSource.TERMINAL)
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
        val code = service.issueCode(PairingService.CodeSource.TERMINAL)
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
            val code = service.issueCode(PairingService.CodeSource.TERMINAL)
            assertEquals(PairingService.CODE_LENGTH, code.length)
            assertTrue(code.none { it in "IO01" }, "ambiguous character in $code")
        }
    }

    /**
     * Rotation is only meaningful if the old code stops working — but retiring it
     * *instantly* makes the ordinary case fail: the phone decodes at t=29.9s, the
     * page rotates at t=30s, the POST lands at t=30.2s. So a superseded code gets a
     * short grace and then dies.
     */
    @Test
    fun `a superseded code keeps working for a grace and then stops`() {
        val first = service.issueCode(PairingService.CodeSource.TERMINAL)
        val second = service.issueCode(PairingService.CodeSource.TERMINAL)

        now = now.plusSeconds(PairingService.SUPERSEDED_GRACE_SECONDS - 1)
        assertIs<PairingService.PairResult.Paired>(
            service.redeem(first, "phone", "1.2.3.4"),
            "a scan that landed just as the code rotated must still pair",
        )
        assertIs<PairingService.PairResult.Paired>(service.redeem(second, "phone", "1.2.3.4"))

        val third = service.issueCode(PairingService.CodeSource.TERMINAL)
        service.issueCode(PairingService.CodeSource.TERMINAL)
        now = now.plusSeconds(PairingService.SUPERSEDED_GRACE_SECONDS + 1)
        assertIs<PairingService.PairResult.Expired>(
            service.redeem(third, "phone", "1.2.3.4"),
            "past the grace, a photographed code must be dead",
        )
    }

    /**
     * The `minOf` guarantee. Superseding must only ever *shorten* a life; a plain
     * assignment would hand a code with ten seconds left another thirty, which turns
     * rotation into a way of keeping old codes alive — the exact opposite of it.
     */
    @Test
    fun `superseding never extends a code's life`() {
        val first = service.issueCode(PairingService.CodeSource.TERMINAL)

        // Ten seconds before its natural death, supersede it.
        now = now.plusSeconds(PairingService.CODE_TTL_SECONDS - 10)
        service.issueCode(PairingService.CodeSource.TERMINAL)

        now = now.plusSeconds(11)
        assertIs<PairingService.PairResult.Expired>(
            service.redeem(first, "phone", "1.2.3.4"),
            "supersession extended the code past its own TTL",
        )
    }

    /**
     * A browser tab left open on `/qr` rotates every 30 seconds. Without scoping, it
     * would retire the code `kiro-bridge pair` just printed, half a minute after the
     * operator read it off their terminal, with nothing anywhere explaining why.
     */
    @Test
    fun `the qr page's rotation does not retire a code printed in a terminal`() {
        val terminal = service.issueCode(PairingService.CodeSource.TERMINAL)

        repeat(5) { service.issueCode(PairingService.CodeSource.QR_PAGE) }
        now = now.plusSeconds(PairingService.SUPERSEDED_GRACE_SECONDS + 5)

        assertIs<PairingService.PairResult.Paired>(service.redeem(terminal, "phone", "1.2.3.4"))
    }

    /** The map is pruned on every issue, so a long-lived page cannot grow it without bound. */
    @Test
    fun `the pending set stays bounded however many codes are issued`() {
        repeat(400) {
            service.issueCode(PairingService.CodeSource.TERMINAL)
            service.issueCode(PairingService.CodeSource.QR_PAGE)
            assertTrue(
                service.pendingCount <= PairingService.MAX_PENDING_CODES,
                "pending codes grew to ${service.pendingCount}",
            )
        }
    }

    /**
     * In steady state — one page rotating — only the current code and the one it
     * superseded survive. That is the invariant the security property rests on, and
     * expiry is what enforces it, not the eviction cap.
     */
    @Test
    fun `a rotating page leaves only the current code and the one it replaced`() {
        repeat(6) {
            service.issueCode(PairingService.CodeSource.QR_PAGE)
            // One second past the grace, which is where the page's own refresh lands
            // -- exactly on the boundary a code is still redeemable, since expiry is
            // strict, and the set momentarily holds three.
            now = now.plusSeconds(PairingService.SUPERSEDED_GRACE_SECONDS + 1)
        }

        assertEquals(2, service.pendingCount, "a rotating page accumulated codes")
    }

    /**
     * `/qr` keeps one session per signed-in identity, so several people can have the
     * page open at once. Eviction must never take a code one of them is looking at
     * right now — which a tight per-source cap would do, silently, on the third.
     */
    @Test
    fun `one person's page does not evict a code another is looking at`() {
        val mine = service.issueCode(PairingService.CodeSource.QR_PAGE)

        // Two more identities open the page in the same instant.
        repeat(2) { service.issueCode(PairingService.CodeSource.QR_PAGE) }

        assertIs<PairingService.PairResult.Paired>(
            service.redeem(mine, "phone", "1.2.3.4"),
            "a code still on someone's screen was evicted",
        )
    }

    /**
     * "Single use" was a comment rather than a guarantee: `redeem` read the code and
     * *then* removed it, so two concurrent POSTs of one code both passed the lookup
     * and both minted a token. Rotation makes a double submit likelier, so this is
     * pinned with real threads rather than reasoned about.
     */
    @Test
    fun `a code cannot be redeemed twice concurrently`() {
        val code = service.issueCode(PairingService.CodeSource.TERMINAL)
        val threads = 16
        val start = java.util.concurrent.CountDownLatch(1)
        val paired = java.util.concurrent.atomic.AtomicInteger()

        val workers = List(threads) { i ->
            Thread {
                start.await()
                if (service.redeem(code, "phone-$i", "10.0.0.$i") is PairingService.PairResult.Paired) {
                    paired.incrementAndGet()
                }
            }.also { it.start() }
        }
        start.countDown()
        workers.forEach { it.join(5_000) }

        assertEquals(1, paired.get(), "a single-use code minted ${paired.get()} tokens")
    }

    @Test
    fun `an unpaired client is not authorised`() {
        assertFalse(service.isAuthorised(null))
        assertFalse(service.isAuthorised(""))
        assertFalse(service.isAuthorised("made-up-token"))
    }
}
