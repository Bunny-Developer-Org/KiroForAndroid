package dev.kiro.bridge

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QrPageBudgetTest {

    private var now = Instant.parse("2026-09-04T12:00:00Z")
    private val budget = QrPageBudget { now }
    private val minted = AtomicInteger()

    private fun next(identity: String = ME, devices: Int = 0) =
        budget.next(identity, devices) { "CODE${minted.incrementAndGet()}" }

    /**
     * The rule the whole bound rests on. Without it, holding F5 mints at HTTP speed
     * and no rotation counter can bound anything.
     */
    @Test
    fun `a reload inside the window shows the same code rather than minting another`() {
        val first = assertIs<QrPageBudget.Decision.Live>(next())

        repeat(10) {
            now = now.plusSeconds(1)
            assertEquals(first.code, assertIs<QrPageBudget.Decision.Live>(next()).code)
        }
        assertEquals(1, minted.get(), "a reload minted a code")
    }

    @Test
    fun `a request after the window mints exactly one new code`() {
        val first = assertIs<QrPageBudget.Decision.Live>(next())
        now = now.plusSeconds(QrPageBudget.ROTATE_SECONDS)

        val second = assertIs<QrPageBudget.Decision.Live>(next())
        assertTrue(second.code != first.code)
        assertEquals(2, minted.get())
        assertEquals(2, second.rotation)
    }

    /** The countdown drives the meta refresh, so it has to mean what it says. */
    @Test
    fun `the countdown falls as the window runs out`() {
        assertEquals(QrPageBudget.ROTATE_SECONDS, assertIs<QrPageBudget.Decision.Live>(next()).secondsUntilRotation)

        now = now.plusSeconds(10)
        assertEquals(
            QrPageBudget.ROTATE_SECONDS - 10,
            assertIs<QrPageBudget.Decision.Live>(next()).secondsUntilRotation,
        )
    }

    @Test
    fun `the session stops after twenty codes`() {
        repeat(QrPageBudget.MAX_ROTATIONS) {
            assertIs<QrPageBudget.Decision.Live>(next())
            now = now.plusSeconds(QrPageBudget.ROTATE_SECONDS)
        }

        assertIs<QrPageBudget.Decision.Stopped>(next())
        assertEquals(QrPageBudget.MAX_ROTATIONS, minted.get(), "a stopped session kept minting")
    }

    /** A tab that is reloaded rarely still ages out, so wall clock bounds it too. */
    @Test
    fun `the session stops after ten minutes even with fewer codes`() {
        assertIs<QrPageBudget.Decision.Live>(next())
        now = now.plusSeconds(QrPageBudget.SESSION_MAX_SECONDS)

        assertIs<QrPageBudget.Decision.Stopped>(next())
    }

    @Test
    fun `a stopped session stays stopped across reloads`() {
        // The clock has to move *after* the first request: a session begins when the
        // page is first asked for, not when the process started.
        assertIs<QrPageBudget.Decision.Live>(next())
        now = now.plusSeconds(QrPageBudget.SESSION_MAX_SECONDS + 1)
        assertIs<QrPageBudget.Decision.Stopped>(next())

        repeat(5) {
            now = now.plusSeconds(60)
            assertIs<QrPageBudget.Decision.Stopped>(next())
        }
    }

    /** The only way back, and it is reachable only from a form POST. */
    @Test
    fun `a reset restores the whole budget`() {
        repeat(QrPageBudget.MAX_ROTATIONS) {
            next()
            now = now.plusSeconds(QrPageBudget.ROTATE_SECONDS)
        }
        assertIs<QrPageBudget.Decision.Stopped>(next())

        budget.reset(ME)

        assertIs<QrPageBudget.Decision.Live>(next())
    }

    /**
     * How an open tab ends in the common, successful case — and the reason the page
     * stops minting the moment the phone actually arrives.
     */
    @Test
    fun `a device arriving ends the session`() {
        assertIs<QrPageBudget.Decision.Live>(next(devices = 2))

        assertIs<QrPageBudget.Decision.Paired>(next(devices = 3))
        assertEquals(1, minted.get(), "a paired session kept minting")
    }

    /** One person's exhausted tab must not lock the bridge's owner out. */
    @Test
    fun `two identities have independent budgets`() {
        repeat(QrPageBudget.MAX_ROTATIONS) {
            next("someone@example.com")
            now = now.plusSeconds(QrPageBudget.ROTATE_SECONDS)
        }
        assertIs<QrPageBudget.Decision.Stopped>(next("someone@example.com"))

        assertIs<QrPageBudget.Decision.Live>(next(ME))
    }

    /** A leak guard, not a capacity plan: a bridge has one owner. */
    @Test
    fun `the ledger cannot grow without bound`() {
        repeat(QrPageBudget.MAX_TRACKED_IDENTITIES * 3) { next("person-$it@example.com") }

        // Nothing observable to assert but the absence of growth, so prove it by the
        // eviction: the very first identity has been forgotten and starts fresh.
        val revisited = assertIs<QrPageBudget.Decision.Live>(next("person-0@example.com"))
        assertEquals(1, revisited.rotation, "the oldest session was never evicted")
    }

    private companion object {
        const val ME = "alex@example.com"
    }
}
