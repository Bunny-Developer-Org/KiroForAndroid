package dev.kiro.bridge

import java.time.Duration
import java.time.Instant

/**
 * How often `/qr` mints a new code, and when it stops.
 *
 * A pairing page left open on a screen is a pairing code being minted every thirty
 * seconds for as long as the tab exists. Two rules bound that, and the first is what
 * makes the second worth anything:
 *
 * **Rotation is time-based, not request-based.** Inside the window a reload returns
 * the *same* code with an updated countdown. Without this, holding F5 mints at HTTP
 * speed and no rotation counter can bound anything; with it, reload-spam is free and
 * the meta refresh is robust to browser jitter and clock skew.
 *
 * **The budget is keyed on the verified Access email**, not a cookie and not a URL
 * parameter: nothing to reset by clearing site data, nothing to leak in a `Referer`,
 * and the bound is per verified human rather than per browser profile. Two browsers
 * for one person sharing a budget is not a cost worth machinery.
 */
internal class QrPageBudget(private val clock: () -> Instant) {

    private val sessions = LinkedHashMap<String, Session>()

    private class Session(
        val startedAt: Instant,
        val devicesAtStart: Int,
        var rotations: Int = 0,
        var code: String? = null,
        var issuedAt: Instant = Instant.EPOCH,
    )

    sealed interface Decision {
        /** A code to show, and how long until it changes. */
        data class Live(val code: String, val secondsUntilRotation: Long, val rotation: Int) : Decision

        /** The session is spent. The page renders no QR, no code, and no refresh. */
        data class Stopped(val rotations: Int) : Decision

        /** A device arrived while this page was open — by far the likeliest way it ends. */
        data object Paired : Decision
    }

    /**
     * @param deviceCount how many devices are paired right now; a rise since the
     *   session began ends it, which is how an open tab stops in the successful case.
     * @param mint called only when a new code is genuinely due, so the caller never
     *   mints one the budget then throws away.
     */
    fun next(identity: String, deviceCount: Int, mint: () -> String): Decision = synchronized(sessions) {
        val now = clock()
        val session = sessions.getOrPut(identity) { Session(startedAt = now, devicesAtStart = deviceCount) }
        evictOldest()

        if (deviceCount > session.devicesAtStart) return Decision.Paired

        val elapsed = Duration.between(session.startedAt, now).seconds
        if (session.rotations >= MAX_ROTATIONS || elapsed >= SESSION_MAX_SECONDS) {
            return Decision.Stopped(session.rotations)
        }

        val due = session.code == null ||
            Duration.between(session.issuedAt, now).seconds >= ROTATE_SECONDS
        if (due) {
            session.code = mint()
            session.issuedAt = now
            session.rotations++
        }

        val left = ROTATE_SECONDS - Duration.between(session.issuedAt, now).seconds
        return Decision.Live(session.code!!, left.coerceAtLeast(0), session.rotations)
    }

    /** Starts a fresh session. Reachable only from a form POST — see `QrRoutes`. */
    fun reset(identity: String): Unit = synchronized(sessions) { sessions.remove(identity) }

    private fun evictOldest() {
        // A bridge has one owner; this is a leak guard, not a capacity plan.
        while (sessions.size > MAX_TRACKED_IDENTITIES) {
            val oldest = sessions.entries.minByOrNull { it.value.startedAt } ?: return
            sessions.remove(oldest.key)
        }
    }

    companion object {
        const val ROTATE_SECONDS: Long = 30

        /** Twenty codes at thirty seconds is ten minutes, so the two bounds agree. */
        const val MAX_ROTATIONS: Int = 20
        const val SESSION_MAX_SECONDS: Long = 600
        const val MAX_TRACKED_IDENTITIES: Int = 64
    }
}
