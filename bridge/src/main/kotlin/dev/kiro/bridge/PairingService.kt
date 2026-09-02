package dev.kiro.bridge

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

    private data class PendingCode(val code: String, val expiresAt: Instant)

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
     * Issues a pairing code for the operator to read off the bridge's console (or
     * scan from the QR it prints). One code at a time: a bridge showing three
     * simultaneously valid codes is a bridge whose operator has lost track of who
     * is pairing.
     */
    public fun issueCode(): String {
        val code = buildString {
            repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) }
        }
        pendingCodes.clear()
        pendingCodes[code] = PendingCode(code, clock().plusSeconds(CODE_TTL_SECONDS))
        return code
    }

    public fun redeem(code: String, deviceName: String, remoteAddress: String): PairResult {
        rateLimit(remoteAddress)?.let { return it }

        val pending = pendingCodes[code.uppercase()]
            ?: return PairResult.BadCode.also { recordAttempt(remoteAddress) }

        // Single-use, whatever the outcome: a code that failed because it expired
        // must not remain redeemable.
        pendingCodes.remove(code.uppercase())

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
        /** No I, O, 0 or 1 — this gets read aloud and typed in by hand. */
        public const val CODE_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        public const val CODE_LENGTH: Int = 8
        public const val CODE_TTL_SECONDS: Long = 300
        public const val TOKEN_BYTES: Int = 32
        public const val MAX_ATTEMPTS_PER_WINDOW: Int = 5
        public const val RATE_WINDOW_SECONDS: Long = 60
        private const val MAX_NAME_LENGTH = 64
    }
}
