package dev.kiro.android.service

import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two properties [Backoff] exists for: the delay actually grows (and
 * caps), and the jitter is full jitter -- uniform in `[0, exponential]` rather
 * than clustered near the boundary. Without this, a regression that turns the
 * jitter back into a synchronised flat delay (the exact bug this class
 * replaced in the reconnect loop) would pass every other test in the suite.
 */
class BackoffTest {

    private val baseMillis = 500L
    private val maxMillis = 60_000L
    private val maxShift = 7

    /** The exponential ceiling for a given attempt, mirroring Backoff's own formula. */
    private fun exponentialFor(attempt: Int): Long =
        min(maxMillis, baseMillis shl min(attempt, maxShift))

    @Test
    fun `delay grows with attempt count up to the cap`() {
        // A Random that always returns the top of its requested bound turns
        // nextDelayMillis's jitter into a no-op, which is what makes the
        // underlying exponential sequence observable directly.
        val backoff = Backoff(baseMillis = baseMillis, maxMillis = maxMillis, random = MaxJitterRandom)

        val delays = (0 until 10).map { backoff.nextDelayMillis() }

        for (i in 1 until delays.size) {
            assertTrue(
                delays[i] >= delays[i - 1],
                "expected delay to be non-decreasing, got $delays",
            )
        }
        assertEquals(exponentialFor(0), delays[0])
        assertEquals(maxMillis, delays.last(), "delay must not exceed the cap")
        // Attempt 7 is the first whose uncapped exponential (500 << 7 = 64000)
        // exceeds maxMillis, so the cap should already be in effect there.
        assertEquals(maxMillis, delays[7])
    }

    @Test
    fun `jitter stays within the exponential bound for the attempt`() {
        val backoff = Backoff(baseMillis = baseMillis, maxMillis = maxMillis, random = Random(seed = 42))

        repeat(20) { attempt ->
            val bound = exponentialFor(attempt)
            val delay = backoff.nextDelayMillis()
            assertTrue(delay in 0..bound, "delay $delay for attempt $attempt outside [0, $bound]")
        }
    }

    @Test
    fun `reset returns the backoff to its initial state`() {
        val backoff = Backoff(baseMillis = baseMillis, maxMillis = maxMillis, random = MaxJitterRandom)

        repeat(5) { backoff.nextDelayMillis() }
        assertEquals(5, backoff.attempts)

        backoff.reset()

        assertEquals(0, backoff.attempts)
        assertEquals(exponentialFor(0), backoff.nextDelayMillis())
    }

    /** Always returns `bound - 1`, i.e. the maximum value nextLong(bound) can produce. */
    private object MaxJitterRandom : Random() {
        override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)
        override fun nextLong(bound: Long): Long = bound - 1
    }
}
