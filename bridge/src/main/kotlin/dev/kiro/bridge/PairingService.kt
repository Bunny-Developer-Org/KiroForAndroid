package dev.kiro.bridge

import dev.kiro.core.auth.PairingPayload
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Auth-1: proving that this phone is allowed to talk to this bridge.
 *
 * Not OAuth and not Kiro's — it exists only because the bridge topology does
 * (AUTHENTICATION §1). Every requirement below is from AUTHENTICATION §4 and all
 * of them are mandatory: single-use codes, short TTL, rate limiting, and a device
 * list the user can revoke from.
 *
 * Device tokens are stored **hashed**. A bridge host that is later compromised
 * should not hand over a working credential for every phone that ever paired.
 */
public class PairingService(
    stateDirectory: File,
    private val clock: () -> Instant = Instant::now,
    private val random: SecureRandom = SecureRandom(),
) {

    private val storeFile = File(stateDirectory, "devices.txt").also { it.parentFile.mkdirs() }

    private val pendingCodes = ConcurrentHashMap<String, PendingCode>()
    private val devices = ConcurrentHashMap<String, Device>()
    private val attempts = ConcurrentHashMap<String, MutableList<Instant>>()

    public data class Device(
        val tokenHash: String,
        val name: String,
        val pairedAt: Instant,
    )

    private data class PendingCode(
        val code: String,
        val expiresAt: Instant,
        val issuedAt: Instant,
        val source: CodeSource,
    )

    /**
     * Which surface printed a code, and therefore which codes it supersedes.
     *
     * Load-bearing rather than bookkeeping: `/qr` rotates every 30 seconds, and
     * without this scoping a browser tab left open on it would retire every code
     * `kiro-bridge pair` printed, half a minute after the operator read it, with
     * nothing anywhere explaining why.
     */
    public enum class CodeSource { TERMINAL, QR_PAGE }

    public sealed interface PairResult {
        public data class Paired(val token: String) : PairResult
        public data object BadCode : PairResult
        public data object Expired : PairResult
        public data class RateLimited(val retryAfterSeconds: Long) : PairResult
    }

    init {
        loadDevices()
    }

    /**
     * Issues a pairing code.
     *
     * `pairingBanner` renders it both ways: as a QR to scan, and as text to read
     * aloud or type when the QR cannot be drawn or scanned.
     *
     * **One code per surface, and a superseded code dies within 30 seconds.** The
     * older rule was "exactly one pending code, and issuing retires the previous one
     * instantly". F-29's `/qr` page broke both halves of it, for two different
     * reasons.
     *
     * It rotates the code every 30s so a QR someone photographed, or saw in a screen
     * share, goes stale almost at once (AUTHENTICATION §4). Instant retirement turns
     * the *ordinary* case into a failure: the phone decodes at t=29.9s, the page
     * rotates at t=30s, the POST lands at t=30.2s, and the user is told their code is
     * not valid on the very screen the feature exists to make pleasant. So
     * supersession shortens a code's life rather than ending it.
     *
     * And retirement is scoped to the [CodeSource] that issued the code, or a browser
     * tab left open would quietly kill every code `kiro-bridge pair` prints.
     *
     * What replaces "exactly one" is this: **every code that will redeem is one a
     * human is looking at right now, or one that was on a screen within the last 30
     * seconds.** In steady state that is two per surface. It is not a brute-force
     * change worth worrying about either way: even at the [MAX_PENDING_CODES]
     * backstop, that many targets in 32^8 at five attempts a minute is nothing.
     *
     * Roads not taken: leaving a superseded code at its full 300s TTL, which would let
     * a photograph from ten rotations ago still pair -- exactly what rotation exists
     * to prevent; and rotating with no grace at all, which is the t=30.2s failure.
     */
    public fun issueCode(source: CodeSource): String {
        val code = buildString {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        }
        val now = clock()
        synchronized(pendingCodes) {
            // Unconditional, every call: this is what keeps the map bounded without
            // depending on anyone ever redeeming anything.
            pendingCodes.values.removeAll { now.isAfter(it.expiresAt) }

            pendingCodes.replaceAll { _, pending ->
                if (pending.source != source) {
                    pending
                } else {
                    // minOf, never assignment. Superseding must only ever *shorten* a
                    // life; assigning would hand a code with five seconds left another
                    // thirty. This is the line most likely to be "simplified" into a bug.
                    pending.copy(expiresAt = minOf(pending.expiresAt, now.plusSeconds(SUPERSEDED_GRACE_SECONDS)))
                }
            }

            pendingCodes[code] = PendingCode(code, now.plusSeconds(CODE_TTL_SECONDS), now, source)

            // Trimmed *within* the source, never globally. Evicting the oldest entry
            // overall would let a page rotating faster than the grace displace a code
            // a terminal just printed -- which is precisely what scoping supersession
            // by source exists to prevent, undone by a tidy-up. The code just issued
            // is excluded so it can never evict itself when several share an instant.
            pendingCodes.values
                .filter { it.source == source && it.code != code }
                .sortedByDescending { it.issuedAt }
                .drop(MAX_PENDING_PER_SOURCE - 1)
                .forEach { pendingCodes.remove(it.code) }
        }
        return code
    }

    public fun redeem(code: String, deviceName: String, remoteAddress: String): PairResult {
        rateLimit(remoteAddress)?.let { return it }

        // Single-use, whatever the outcome -- a code that failed because it expired
        // must not remain redeemable. One atomic remove rather than a get followed by
        // a remove: the two-step version let two concurrent POSTs of the same code
        // both pass the lookup and both mint a token, which made "single-use" a
        // comment rather than a guarantee. Rotation makes a double submit likelier.
        val pending = pendingCodes.remove(code.uppercase())
            ?: return PairResult.BadCode.also { recordAttempt(remoteAddress) }

        if (clock().isAfter(pending.expiresAt)) return PairResult.Expired

        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        val device = Device(hash(token), deviceName.take(MAX_NAME_LENGTH), clock())
        devices[device.tokenHash] = device
        persistDevices()
        return PairResult.Paired(token)
    }

    /** Constant-time-ish check against stored hashes. */
    public fun isAuthorised(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        return devices.containsKey(hash(token))
    }

    public fun listDevices(): List<Device> = devices.values.sortedBy { it.pairedAt }

    /** Test-only window on the pending map, mirroring `BridgeServer.clientCount`. */
    internal val pendingCount: Int get() = pendingCodes.size

    public fun revoke(tokenHash: String): Boolean {
        val removed = devices.remove(tokenHash) != null
        if (removed) persistDevices()
        return removed
    }

    public fun revokeAll() {
        devices.clear()
        persistDevices()
    }

    private fun rateLimit(remoteAddress: String): PairResult.RateLimited? {
        val now = clock()
        val window = attempts.getOrPut(remoteAddress) { mutableListOf() }
        synchronized(window) {
            window.removeAll { it.isBefore(now.minusSeconds(RATE_WINDOW_SECONDS)) }
            if (window.size >= MAX_ATTEMPTS_PER_WINDOW) {
                val oldest = window.minOrNull() ?: now
                val retryAfter = RATE_WINDOW_SECONDS - (now.epochSecond - oldest.epochSecond)
                return PairResult.RateLimited(retryAfter.coerceAtLeast(1))
            }
        }
        return null
    }

    private fun recordAttempt(remoteAddress: String) {
        val window = attempts.getOrPut(remoteAddress) { mutableListOf() }
        synchronized(window) { window.add(clock()) }
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .let { Base64.getEncoder().encodeToString(it) }

    private fun loadDevices() {
        if (!storeFile.exists()) return
        storeFile.readLines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3) {
                devices[parts[0]] = Device(parts[0], parts[1], Instant.parse(parts[2]))
            }
        }
    }

    private fun persistDevices() {
        storeFile.writeText(
            devices.values.joinToString("\n") { "${it.tokenHash}\t${it.name}\t${it.pairedAt}" },
        )
        // Best effort: on a shared host the device list should not be world-readable.
        runCatching {
            storeFile.setReadable(false, false)
            storeFile.setReadable(true, true)
            storeFile.setWritable(false, false)
            storeFile.setWritable(true, true)
        }
    }

    public companion object {
        /**
         * Defined in `core/` so the app cannot disagree with the bridge about what a
         * code looks like — it has to recognise one coming out of a QR.
         */
        public const val CODE_ALPHABET: String = PairingPayload.CODE_ALPHABET
        public const val CODE_LENGTH: Int = PairingPayload.CODE_LENGTH
        public const val CODE_TTL_SECONDS: Long = 300

        /**
         * How long a superseded code keeps working. Equal to `/qr`'s rotation period,
         * which makes the rule one sentence: the code on screen and the one it
         * replaced, and never further back.
         */
        public const val SUPERSEDED_GRACE_SECONDS: Long = 30

        /**
         * A backstop against pathological growth, not the mechanism that keeps the
         * set small. **Expiry is that mechanism**: every issue prunes what has
         * expired, supersession cuts a same-source code to 30 seconds, and a code
         * lives 300 at the outside.
         *
         * Sized well above the steady state on purpose. A tight count looks tidier
         * and silently breaks the invariant this class promises: `/qr` keeps one
         * session *per signed-in identity*, so with a cap of two, a third person
         * opening the page would evict a code the first is looking at right now,
         * with a countdown still ticking. Eviction must never be able to do that,
         * so the cap sits where only a pathology reaches it -- at which point
         * dropping the oldest is the right call anyway.
         */
        public const val MAX_PENDING_PER_SOURCE: Int = 128

        /** Derived: [CodeSource] has two entries. */
        public const val MAX_PENDING_CODES: Int = MAX_PENDING_PER_SOURCE * 2
        public const val TOKEN_BYTES: Int = 32
        public const val MAX_ATTEMPTS_PER_WINDOW: Int = 5
        public const val RATE_WINDOW_SECONDS: Long = 60
        private const val MAX_NAME_LENGTH = 64
    }
}
