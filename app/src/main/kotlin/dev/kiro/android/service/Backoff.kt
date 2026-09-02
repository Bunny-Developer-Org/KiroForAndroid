package dev.kiro.android.service

import kotlin.math.min
import kotlin.random.Random

/**
 * Reconnect delay: exponential, capped, with jitter.
 *
 * The jitter is not decoration. Every phone on a flaky network reconnects when
 * the network returns, and without it they all retry in lockstep — which is a
 * bridge on someone's Raspberry Pi taking a synchronised thundering herd.
 */
class Backoff(
    private val baseMillis: Long = 500,
    private val maxMillis: Long = 60_000,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    val attempts: Int get() = attempt

    fun nextDelayMillis(): Long {
        val exponential = min(maxMillis, baseMillis shl min(attempt, MAX_SHIFT))
        attempt++
        // Full jitter: uniform in [0, exponential]. Retries spread out instead of
        // clustering at the boundary the way half-jitter still does.
        return random.nextLong(exponential.coerceAtLeast(1) + 1)
    }

    /**
     * Called on connectivity-regained, not only on success: waiting out a 60s
     * backoff when the network just came back is the difference between an app
     * that feels alive and one that feels broken.
     */
    fun reset() {
        attempt = 0
    }

    private companion object {
        const val MAX_SHIFT = 7
    }
}
