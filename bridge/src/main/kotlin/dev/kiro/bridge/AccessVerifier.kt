package dev.kiro.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Proves that a `/qr` request really came through Cloudflare Access, as the person
 * it claims.
 *
 * **The `Cf-Access-*` headers are not evidence.** Cloudflare strips inbound copies
 * on requests it proxies, but the bridge's origin port is reachable without going
 * through the tunnel at all -- the same flattening that made the control channel a
 * Unix socket (see [ControlSocket]). Anything that reaches the origin can assert
 * whatever identity it likes, so the assertion is verified here, cryptographically,
 * and the identity `/qr` displays comes out of the verified token rather than out
 * of `Cf-Access-Authenticated-User-Email`.
 *
 * Hand-rolled rather than pulling in `nimbus-jose-jwt` or `java-jwt`. We need
 * exactly one algorithm and four claims, and *algorithm agility is the main risk
 * surface of a general-purpose JWT library* -- the thing a library would give us is
 * precisely the thing we do not want. The explicit `alg == "RS256"` check below is
 * what buys back the safety a library's own defaults would have provided; without
 * it this file would be one "generalisation" away from accepting `alg: none`.
 */
internal class AccessVerifier(
    private val teamDomain: String,
    private val audience: String,
    private val clock: () -> Instant,
    /** Overridable so tests can serve a real JWKS from a real loopback server. */
    private val jwksUrl: String = jwksUrlFor(teamDomain),
    private val http: HttpClient = defaultHttpClient(),
) {

    private val log = LoggerFactory.getLogger(AccessVerifier::class.java)
    private val fetchLock = Mutex()

    @Volatile
    private var cache: KeyCache? = null

    /**
     * When a fetch was last *attempted*, successful or not.
     *
     * Separate from [KeyCache.fetchedAt] on purpose: a stale serve does not advance
     * that, so it cannot rate-limit retries against an endpoint that is failing.
     */
    @Volatile
    private var lastAttemptAt: Instant? = null

    private class KeyCache(val keys: Map<String, RSAPublicKey>, val fetchedAt: Instant)

    sealed interface Result {
        data class Verified(val email: String) : Result
        data class Rejected(val reason: String) : Result
    }

    /** Pre-warms the key cache so a typo'd team domain is visible at startup, not first use. */
    suspend fun warmUp() {
        runCatching { keys(force = false) }
            .onSuccess { log.info("Access: {}, {} signing key(s)", teamDomain, it.size) }
            .onFailure { log.warn("Access: could not reach {} -- /qr will refuse until it can", jwksUrl) }
    }

    suspend fun verify(assertion: String?): Result {
        if (assertion.isNullOrBlank()) return Result.Rejected("no assertion header")
        val parts = assertion.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return Result.Rejected("not a three-part JWT")

        val header = decodeJson(parts[0]) ?: return Result.Rejected("unreadable header")
        val payload = decodeJson(parts[1]) ?: return Result.Rejected("unreadable payload")

        // First, before a key is even looked up. Rejects `alg: none`, and rejects the
        // HS256 confusion attack where the token is HMAC'd with the RSA *public* key
        // -- which is public, so anyone could mint one.
        if (header.string("alg") != "RS256") return Result.Rejected("alg is ${header.string("alg")}, not RS256")

        claimFailure(payload)?.let { return Result.Rejected(it) }

        val kid = header.string("kid") ?: return Result.Rejected("no kid")
        val key = signingKey(kid) ?: return Result.Rejected("no published key for kid $kid")
        if (!signatureValid(parts, key)) return Result.Rejected("signature does not verify")

        val email = payload.string("email")
        // A service-token identity carries `common_name` and no email. /qr mints
        // pairing codes for a human holding a phone; a machine credential parked in
        // some CI config has no business doing that.
        return if (email.isNullOrBlank()) Result.Rejected("no email claim") else Result.Verified(email)
    }

    private fun claimFailure(payload: JsonObject): String? {
        val now = clock().epochSecond
        // Required, not optional. The reference implementation this mirrors treats a
        // *missing* exp as valid, which makes such a token immortal.
        val exp = payload.long("exp") ?: return "no exp claim"
        if (exp + CLOCK_SKEW_SECONDS < now) return "expired"
        payload.long("nbf")?.let { if (it - CLOCK_SKEW_SECONDS > now) return "not yet valid" }

        val issuer = payload.string("iss")
        if (issuer != "https://$teamDomain") return "issuer is $issuer"
        if (audience !in payload.audiences()) return "audience does not include $audience"
        return null
    }

    private fun signatureValid(parts: List<String>, key: RSAPublicKey): Boolean = runCatching {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verify(URL_DECODER.decode(parts[2]))
        }
    }.getOrDefault(false)

    /**
     * Looks a key up, refetching once if the `kid` is unknown.
     *
     * Without that refetch, a Cloudflare signing-key rotation would reject every
     * request until the cache aged out -- up to an hour of a broken page. The
     * [JWKS_MIN_REFRESH_SECONDS] floor is what stops someone spraying forged `kid`s
     * from turning this into a fetch amplifier pointed at Cloudflare.
     */
    private suspend fun signingKey(kid: String): RSAPublicKey? {
        // Every path here swallows a fetch failure into null, and that is the whole
        // fail-closed contract: `verify` must return Rejected rather than throw, or
        // an unreachable certs endpoint becomes a 500 on /qr instead of a 403 that
        // explains itself.
        val cached = attempt { keys(force = false) } ?: return null
        cached[kid]?.let { return it }

        // Rate-limited on *attempts*, not successes. Gating on `fetchedAt` looks
        // equivalent and is not: a stale serve never advances it, so while the certs
        // endpoint is failing every request would launch another five-second fetch,
        // all queued behind one lock -- unbounded outbound traffic and unbounded /qr
        // latency, from a request that needs no valid signature to get this far.
        val attemptedAt = lastAttemptAt ?: return null
        if (Duration.between(attemptedAt, clock()).seconds < JWKS_MIN_REFRESH_SECONDS) return null
        return attempt { keys(force = true, unlessAttemptedSince = attemptedAt) }?.get(kid)
    }

    /** Like `runCatching`, but never swallows cancellation. */
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug("access key lookup failed", e)
        null
    }

    private fun ageOf(cache: KeyCache?): Long? = cache?.let { Duration.between(it.fetchedAt, clock()).seconds }

    /** The cached keys if they are still inside their TTL, else null. */
    private fun freshKeys(): Map<String, RSAPublicKey>? {
        val current = cache ?: return null
        val age = ageOf(current) ?: return null
        return current.keys.takeIf { age < JWKS_TTL_SECONDS }
    }

    /**
     * @param unlessAttemptedSince when set, gives up the forced fetch if anybody else
     *   attempted one while this call waited for the lock. Without it, a burst of
     *   requests carrying unknown `kid`s all pass the rate check before the first
     *   fetch lands, and every one of them then fetches -- the limit would bound
     *   bursts per minute rather than fetches.
     */
    private suspend fun keys(force: Boolean, unlessAttemptedSince: Instant? = null): Map<String, RSAPublicKey> {
        if (!force) freshKeys()?.let { return it }

        return fetchLock.withLock {
            // Re-check inside the lock: ten simultaneous first requests must produce
            // one fetch, not ten.
            val latest = cache
            if (!force) freshKeys()?.let { return@withLock it }
            if (unlessAttemptedSince != null && lastAttemptAt != unlessAttemptedSince) {
                return@withLock latest?.keys ?: emptyMap()
            }
            lastAttemptAt = clock()
            try {
                val fetched = fetchKeys()
                cache = KeyCache(fetched, clock())
                fetched
            } catch (e: Exception) {
                // A warm cache outlives a failing certs endpoint, for a day. This is
                // not fail-open: the RS256 check still runs, against keys Cloudflare
                // really published. Refusing here instead would let any transient
                // blip at Cloudflare break pairing entirely.
                val stale = latest ?: throw e
                val staleAge = ageOf(stale) ?: throw e
                if (staleAge > JWKS_STALE_MAX_SECONDS) throw e
                log.warn("Access: {} unreachable, using keys cached {}s ago", jwksUrl, staleAge)
                stale.keys
            }
        }
    }

    private suspend fun fetchKeys(): Map<String, RSAPublicKey> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(jwksUrl))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "$jwksUrl answered ${response.statusCode()}" }
        // Parsed once here rather than per request.
        AccessJson.decodeFromString<Jwks>(response.body()).keys
            .filter { it.kty == "RSA" && it.kid != null && it.n != null && it.e != null }
            .associate { it.kid!! to rsaKey(it.n!!, it.e!!) }
            .also { check(it.isNotEmpty()) { "$jwksUrl published no usable RSA keys" } }
    }

    @Serializable
    private data class Jwks(val keys: List<Jwk> = emptyList())

    @Serializable
    private data class Jwk(
        val kty: String? = null,
        val kid: String? = null,
        val n: String? = null,
        val e: String? = null,
    )

    companion object {
        const val CLOCK_SKEW_SECONDS: Long = 60
        const val JWKS_TTL_SECONDS: Long = 3600
        const val JWKS_MIN_REFRESH_SECONDS: Long = 60
        const val JWKS_STALE_MAX_SECONDS: Long = 86_400
        private const val REQUEST_TIMEOUT_SECONDS = 5L
        private const val CONNECT_TIMEOUT_SECONDS = 3L

        private val URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()
        private val AccessJson = Json { ignoreUnknownKeys = true }

        fun jwksUrlFor(teamDomain: String): String = "https://$teamDomain/cdn-cgi/access/certs"

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build()

        /**
         * The signum argument is mandatory, not decorative: every 2048-bit RSA modulus
         * has its top bit set, so `BigInteger(bytes)` reads it as a negative number and
         * *every* verification fails, with a signature error that says nothing about
         * the real cause.
         */
        private fun rsaKey(n: String, e: String): RSAPublicKey = KeyFactory.getInstance("RSA")
            .generatePublic(
                RSAPublicKeySpec(
                    BigInteger(1, URL_DECODER.decode(n)),
                    BigInteger(1, URL_DECODER.decode(e)),
                ),
            ) as RSAPublicKey

        /** Base64url is unpadded in JWTs; `getUrlDecoder` accepts that without help. */
        private fun decodeJson(segment: String): JsonObject? = runCatching {
            AccessJson.parseToJsonElement(String(URL_DECODER.decode(segment), Charsets.UTF_8)) as? JsonObject
        }.getOrNull()

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

        /** `aud` is a string or an array of them, per RFC 7519. */
        private fun JsonObject.audiences(): List<String> = when (val aud = this["aud"]) {
            is JsonPrimitive -> listOf(aud.content)
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
    }
}
