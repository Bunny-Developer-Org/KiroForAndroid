package dev.kiro.bridge

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `/qr` mints pairing codes, so the only thing standing between it and the public
 * internet is this class. Everything here runs against a **real** RSA keypair and a
 * **real** JWKS served over a **real** loopback HTTP server — the same reasoning
 * `BridgeServerTest` gives for using real sockets: a mock would have let the
 * signum bug in `rsaKey` through, and that bug rejects every token ever issued.
 */
class AccessVerifierTest {

    private lateinit var server: HttpServer
    private lateinit var keyPair: KeyPair
    private var now = Instant.parse("2026-09-04T12:00:00Z")
    private val fetches = AtomicInteger()

    /** Swapped by the rotation test; `null` makes the endpoint start failing. */
    private var published: Map<String, KeyPair>? = null

    @BeforeTest
    fun startJwks() {
        keyPair = generateKeyPair()
        published = mapOf(KID to keyPair)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/certs") { exchange ->
            fetches.incrementAndGet()
            val keys = published
            if (keys == null) {
                exchange.sendResponseHeaders(500, -1)
            } else {
                val body = jwks(keys).toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterTest
    fun stopJwks() = server.stop(0)

    private fun verifier(audience: String = AUD, url: String = "http://127.0.0.1:${server.address.port}/certs") =
        AccessVerifier(TEAM, audience, clock = { now }, jwksUrl = url)

    @Test
    fun `a token signed by the team key, for this audience, names the signed-in person`(): Unit = runBlocking {
        val result = verifier().verify(token())

        assertEquals("alex@example.com", assertIs<AccessVerifier.Result.Verified>(result).email)
    }

    /** The whole reason this class exists: a forged header must not get past it. */
    @Test
    fun `a token signed by a key the team does not publish is refused`(): Unit = runBlocking {
        val attacker = generateKeyPair()

        assertIs<AccessVerifier.Result.Rejected>(verifier().verify(token(signWith = attacker)))
    }

    /**
     * The two canonical JWT holes. `alg: none` is self-explanatory; `HS256` is the
     * confusion attack where the token is HMAC'd with the RSA **public** key, which
     * is public, so anyone could mint one.
     */
    @Test
    fun `alg none and alg HS256 are both refused`(): Unit = runBlocking {
        assertIs<AccessVerifier.Result.Rejected>(verifier().verify(token(alg = "none")))
        assertIs<AccessVerifier.Result.Rejected>(verifier().verify(token(alg = "HS256")))
    }

    /** An org with two Access applications must not have one open the other. */
    @Test
    fun `a token for another application in the same team is refused`(): Unit = runBlocking {
        assertIs<AccessVerifier.Result.Rejected>(verifier(audience = "a-different-application").verify(token()))
    }

    @Test
    fun `a token issued by another team is refused`(): Unit = runBlocking {
        val claims = claims() + ("iss" to "\"https://someone-else.cloudflareaccess.com\"")

        assertIs<AccessVerifier.Result.Rejected>(verifier().verify(token(claims = claims)))
    }

    @Test
    fun `an expired token is refused and one expiring shortly is not`(): Unit = runBlocking {
        val expired = token(claims = claims(exp = now.epochSecond - 600))
        assertIs<AccessVerifier.Result.Rejected>(verifier().verify(expired))

        val live = token(claims = claims(exp = now.epochSecond + 60))
        assertIs<AccessVerifier.Result.Verified>(verifier().verify(live))
    }

    @Test
    fun `a token that is not yet valid is refused`(): Unit = runBlocking {
        val future = token(claims = claims() + ("nbf" to "${now.epochSecond + 600}"))

        assertIs<AccessVerifier.Result.Rejected>(verifier().verify(future))
    }

    /**
     * A GCE VM with drifting NTP would otherwise 403 intermittently, and the symptom
     * looks exactly like a Cloudflare outage rather than a clock.
     */
    @Test
    fun `a minute of clock skew is tolerated at both ends`(): Unit = runBlocking {
        val justExpired = token(claims = claims(exp = now.epochSecond - 30))
        assertIs<AccessVerifier.Result.Verified>(verifier().verify(justExpired))

        val justFuture = token(claims = claims() + ("nbf" to "${now.epochSecond + 30}"))
        assertIs<AccessVerifier.Result.Verified>(verifier().verify(justFuture))
    }

    /** A token with no expiry never expires. The reference implementation allows this. */
    @Test
    fun `a token with no exp claim is refused`(): Unit = runBlocking {
        val claims = claims().filterKeys { it != "exp" }

        assertIs<AccessVerifier.Result.Rejected>(verifier().verify(token(claims = claims)))
    }

    /** A machine credential parked in a CI config must not mint pairing codes. */
    @Test
    fun `a service token identity with no email is refused`(): Unit = runBlocking {
        val claims = claims().filterKeys { it != "email" } + ("common_name" to "\"ci-runner\"")

        assertIs<AccessVerifier.Result.Rejected>(verifier().verify(token(claims = claims)))
    }

    @Test
    fun `the jwks is fetched once and reused across many verifications`(): Unit = runBlocking {
        val verifier = verifier()
        repeat(5) { assertIs<AccessVerifier.Result.Verified>(verifier.verify(token())) }

        assertEquals(1, fetches.get(), "the certs endpoint was hit ${fetches.get()} times")
    }

    /**
     * Cloudflare rotates signing keys. Without the unknown-kid refetch, every request
     * would be refused until the cache aged out — up to an hour of a dead page.
     */
    @Test
    fun `a rotated signing key is picked up without waiting out the cache`(): Unit = runBlocking {
        val verifier = verifier()
        assertIs<AccessVerifier.Result.Verified>(verifier.verify(token()))

        val rotated = generateKeyPair()
        published = mapOf("kid-2" to rotated)
        now = now.plusSeconds(AccessVerifier.JWKS_MIN_REFRESH_SECONDS + 1)

        val result = verifier.verify(token(kid = "kid-2", signWith = rotated))
        assertIs<AccessVerifier.Result.Verified>(result)
    }

    /**
     * ...but an unknown kid must not be a free way to make the bridge hammer
     * Cloudflare: the refetch is rate-limited, so a spray of forged kids costs one
     * fetch, not one each.
     */
    @Test
    fun `forged kids cannot turn the bridge into a jwks fetch amplifier`(): Unit = runBlocking {
        val verifier = verifier()
        assertIs<AccessVerifier.Result.Verified>(verifier.verify(token()))
        val afterWarmUp = fetches.get()

        repeat(20) { assertIs<AccessVerifier.Result.Rejected>(verifier.verify(token(kid = "forged-$it"))) }

        assertEquals(afterWarmUp, fetches.get(), "each unknown kid triggered its own fetch")
    }

    /** Nothing to verify against means nothing gets verified. */
    @Test
    fun `a cold cache and an unreachable endpoint refuses`(): Unit = runBlocking {
        val unreachable = verifier(url = "http://127.0.0.1:${freePort()}/certs")

        assertIs<AccessVerifier.Result.Rejected>(unreachable.verify(token()))
    }

    /**
     * A warm cache outlives a failing certs endpoint. Not fail-open — the signature
     * is still checked against keys Cloudflare really published — and the alternative
     * is a transient Cloudflare blip breaking pairing outright.
     */
    @Test
    fun `a warm cache survives an endpoint that has started failing`(): Unit = runBlocking {
        val verifier = verifier()
        assertIs<AccessVerifier.Result.Verified>(verifier.verify(token()))

        published = null
        now = now.plusSeconds(AccessVerifier.JWKS_TTL_SECONDS + 1)
        assertIs<AccessVerifier.Result.Verified>(verifier.verify(token()))

        now = now.plusSeconds(AccessVerifier.JWKS_STALE_MAX_SECONDS + 1)
        assertIs<AccessVerifier.Result.Rejected>(verifier.verify(token()))
    }

    /** Ten simultaneous first requests must produce one fetch, not ten. */
    @Test
    fun `concurrent first requests fetch the jwks once`(): Unit = runBlocking {
        val verifier = verifier()

        val results = (1..10).map { async { verifier.verify(token()) } }.awaitAll()

        assertTrue(results.all { it is AccessVerifier.Result.Verified })
        assertEquals(1, fetches.get(), "the certs endpoint was hit ${fetches.get()} times")
    }

    @Test
    fun `a missing or malformed assertion is refused without a fetch`(): Unit = runBlocking {
        val verifier = verifier()

        assertIs<AccessVerifier.Result.Rejected>(verifier.verify(null))
        assertIs<AccessVerifier.Result.Rejected>(verifier.verify(""))
        assertIs<AccessVerifier.Result.Rejected>(verifier.verify("not.a.jwt"))
        assertIs<AccessVerifier.Result.Rejected>(verifier.verify("onlyonepart"))
        assertEquals(0, fetches.get(), "a malformed token should never reach the network")
    }

    // --- fixtures -----------------------------------------------------------

    private fun claims(exp: Long = now.epochSecond + 3600): Map<String, String> = mapOf(
        "iss" to "\"https://$TEAM\"",
        "aud" to "[\"$AUD\"]",
        "email" to "\"alex@example.com\"",
        "exp" to "$exp",
    )

    private fun token(
        claims: Map<String, String> = claims(),
        alg: String = "RS256",
        kid: String = KID,
        signWith: KeyPair = keyPair,
    ): String {
        val header = b64("""{"alg":"$alg","kid":"$kid","typ":"JWT"}""".toByteArray())
        val payload = b64(claims.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }.toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signWith.private)
            update("$header.$payload".toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$header.$payload.${b64(signature)}"
    }

    private fun jwks(keys: Map<String, KeyPair>): String {
        val entries = keys.entries.joinToString(",") { (kid, pair) ->
            val pub = pair.public as java.security.interfaces.RSAPublicKey
            """{"kty":"RSA","alg":"RS256","use":"sig","kid":"$kid",""" +
                """"n":"${b64(unsigned(pub.modulus))}","e":"${b64(unsigned(pub.publicExponent))}"}"""
        }
        return """{"keys":[$entries]}"""
    }

    /**
     * Strips the sign byte `BigInteger.toByteArray()` prepends. The production
     * decoder's `BigInteger(1, …)` would cope either way, but a spec-wrong fixture
     * would hide a real bug rather than expose one.
     */
    private fun unsigned(value: BigInteger): ByteArray =
        value.toByteArray().let { if (it.isNotEmpty() && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private companion object {
        const val TEAM = "acme.cloudflareaccess.com"
        const val AUD = "0123456789abcdef"
        const val KID = "kid-1"
        const val KEY_BITS = 2048
    }
}
