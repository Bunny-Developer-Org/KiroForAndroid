package dev.kiro.bridge

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `/qr` end to end, through a real [BridgeServer] on a real port, against a real
 * JWKS served from a real keypair.
 *
 * Its own class rather than more cases in `BridgeServerTest`, whose KDoc is
 * specifically about the client-roster leak and should stay about that.
 */
class QrPageTest {

    private lateinit var scope: CoroutineScope
    private lateinit var jwks: HttpServer
    private lateinit var keyPair: KeyPair
    private lateinit var pairing: PairingService
    private var port = 0
    private var now = Instant.parse("2026-09-04T12:00:00Z")

    @BeforeTest
    fun start() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        jwks = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        jwks.createContext("/certs") { exchange ->
            val pub = keyPair.public as java.security.interfaces.RSAPublicKey
            val body = """{"keys":[{"kty":"RSA","kid":"$KID","alg":"RS256",""" +
                """"n":"${b64(unsigned(pub.modulus))}","e":"${b64(unsigned(pub.publicExponent))}"}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        jwks.start()
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        jwks.stop(0)
    }

    /** @param access false builds a bridge with no Access configuration at all. */
    private fun serve(access: Boolean = true) {
        port = java.net.ServerSocket(0).use { it.localPort }
        val stateDir = Files.createTempDirectory("qr-page-test").toFile()
        val config = BridgeConfig(
            port = port,
            stateDirectory = stateDir,
            publicUrl = ADVERTISED,
            accessTeamDomain = if (access) TEAM else null,
            accessAudience = if (access) AUD else null,
        )
        pairing = PairingService(stateDir, clock = { now })
        val verifier = if (access) {
            AccessVerifier(TEAM, AUD, clock = { now }, jwksUrl = "http://127.0.0.1:${jwks.address.port}/certs")
        } else {
            null
        }
        BridgeServer(config, CliSupervisor(config, scope), pairing, scope, verifier, QrPageBudget { now })
            .start(wait = false)
        awaitListening()
    }

    @Test
    fun `qr is refused when Access is unconfigured, and names the flags that enable it`() {
        serve(access = false)

        val response = get("/qr")

        assertEquals(403, response.statusCode())
        assertTrue("--access-team-domain" in response.body(), response.body())
        assertTrue("--access-aud" in response.body())
        assertTrue("/qr path" in response.body(), "it must warn about scoping the Access application")
    }

    @Test
    fun `qr is refused with no assertion header`() {
        serve()

        val response = get("/qr")

        assertEquals(403, response.statusCode())
        assertTrue("did not come through Cloudflare Access" in response.body(), response.body())
    }

    /** The forged-header case, end to end. This is what the whole design is for. */
    @Test
    fun `qr is refused for a token signed by a key the team does not publish`() {
        serve()
        val attacker = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val response = get("/qr", token(signWith = attacker))

        assertEquals(403, response.statusCode())
        assertTrue("<svg" !in response.body(), "a refused request was still shown a QR")
    }

    /** A fail-closed route that leaks on the way out is not fail-closed. */
    @Test
    fun `a refused request leaks no code, address or device name`() {
        serve()
        val code = pairing.issueCode(PairingService.CodeSource.TERMINAL)
        pairing.redeem(code, "Pawel's Pixel", "1.2.3.4")

        val body = get("/qr").body()

        assertFalse("Pawel's Pixel" in body, "a paired device name reached a 403")
        assertFalse(Regex("""\b[A-Z2-9]{8}\b""").containsMatchIn(body), "something code-shaped reached a 403: $body")
        assertFalse(ADVERTISED in body, "the advertised address reached a 403")
    }

    /** The acceptance test for the whole feature. */
    @Test
    fun `a verified request gets a page whose code redeems at POST pair`() {
        serve()

        val page = get("/qr", token())
        assertEquals(200, page.statusCode())
        assertTrue("<svg" in page.body(), "no QR on the page")
        assertTrue("alex@example.com" in page.body(), "the verified identity is not shown")

        val code = codeFrom(page.body())
        val paired = post("/pair", """{"code":"$code","deviceName":"Pixel 8a"}""")
        assertEquals(200, paired.statusCode())
        assertTrue("token" in paired.body(), paired.body())
    }

    /**
     * Rotation, and the grace that makes it usable: the code the page replaced must
     * still pair for a moment, because a scan lands a fraction of a second after the
     * picture changes far more often than anyone expects.
     */
    @Test
    fun `the code rotates, and the one it replaced still pairs briefly`() {
        serve()
        val first = codeFrom(get("/qr", token()).body())

        now = now.plusSeconds(QrPageBudget.ROTATE_SECONDS)
        val second = codeFrom(get("/qr", token()).body())
        assertTrue(first != second, "the code did not rotate")

        // The late scan -- decoded just before the picture changed, arriving just
        // after. Redeeming ends the session, so this is the last act of the test.
        assertEquals(
            200,
            post("/pair", """{"code":"$first","deviceName":"late scan"}""").statusCode(),
            "a scan that landed a moment after the rotation was rejected",
        )
    }

    /** Past the grace, a photographed code is dead — which is the point of rotating. */
    @Test
    fun `a code from two rotations ago no longer pairs`() {
        serve()
        val first = codeFrom(get("/qr", token()).body())

        now = now.plusSeconds(QrPageBudget.ROTATE_SECONDS)
        codeFrom(get("/qr", token()).body())
        now = now.plusSeconds(PairingService.SUPERSEDED_GRACE_SECONDS + 1)

        assertEquals(
            403,
            post("/pair", """{"code":"$first","deviceName":"photographed"}""").statusCode(),
            "a code well past its grace still paired",
        )
    }

    /** How an open tab stops in the successful case: no QR, no code, no refresh. */
    @Test
    fun `once a device pairs, the page stops offering codes`() {
        serve()
        val code = codeFrom(get("/qr", token()).body())
        assertEquals(200, post("/pair", """{"code":"$code","deviceName":"Pixel 8a"}""").statusCode())

        val page = get("/qr", token()).body()

        assertTrue("Paired" in page, page)
        assertTrue("<svg" !in page, "the page kept showing a QR after a device paired")
        assertTrue("http-equiv=\"refresh\"" !in page, "the page kept refreshing after a device paired")
    }

    /**
     * The test that stops a future "let's just protect the whole hostname". The phone
     * cannot complete an interactive browser sign-in, so an Access application
     * covering `/pair` and `/acp` breaks pairing *and* the session socket, with an
     * error pointing nowhere near Cloudflare.
     */
    @Test
    fun `pair and acp never require Access`() {
        serve()
        val code = pairing.issueCode(PairingService.CodeSource.TERMINAL)

        // No Cf-Access-Jwt-Assertion header anywhere in this test.
        val paired = post("/pair", """{"code":"$code","deviceName":"Pixel 8a"}""")
        assertEquals(200, paired.statusCode(), paired.body())
        val token = Regex(""""token":"([^"]+)"""").find(paired.body())!!.groupValues[1]

        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("X-Kiro-Bridge-Token", token)
            .buildAsync(URI.create("ws://127.0.0.1:$port/acp"), object : WebSocket.Listener {})
            .get(5, TimeUnit.SECONDS)
        assertFalse(socket.isInputClosed, "the agent socket was refused without an Access session")
    }

    /** A pairing code sitting in a browser or proxy cache is the leak rotation prevents. */
    @Test
    fun `every qr response forbids caching`() {
        serve()

        listOf(get("/qr"), get("/qr", token())).forEach {
            assertEquals("no-store", it.headers().firstValue("cache-control").orElse(null))
        }
    }

    /**
     * Cloudflare Access issues its session cookie `SameSite=None`, and the edge turns
     * that cookie into the assertion header — so a hostile page an operator merely
     * visits can auto-submit this form with a live session attached. Looping
     * reset+GET would then mint a code every 30 seconds forever, which is exactly the
     * state `QrPageBudget` exists to prevent. `X-Frame-Options` stops the response
     * rendering, not the request arriving.
     */
    @Test
    fun `a cross-origin POST cannot reset the budget`() {
        serve()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/qr"))
                .header("Cf-Access-Jwt-Assertion", token())
                .header("Origin", "https://attacker.example")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(403, response.statusCode(), "a cross-origin reset was accepted")
    }

    /** ...while the page's own button, which is same-origin, still works. */
    @Test
    fun `a same-origin POST resets the budget`() {
        serve()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/qr"))
                .header("Cf-Access-Jwt-Assertion", token())
                .header("Origin", "http://127.0.0.1:$port")
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(303, response.statusCode(), response.body())
    }

    @Test
    fun `posting to qr without Access resets nothing`() {
        serve()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/qr"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(403, response.statusCode())
    }

    // --- helpers ------------------------------------------------------------

    private fun codeFrom(page: String): String =
        Regex("""class="code">([A-Z2-9]{8})<""").find(page)?.groupValues?.get(1)
            ?: error("no pairing code on the page: $page")

    private fun get(path: String, assertion: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        assertion?.let { builder.header("Cf-Access-Jwt-Assertion", it) }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun token(signWith: KeyPair = keyPair): String {
        val header = b64("""{"alg":"RS256","kid":"$KID","typ":"JWT"}""".toByteArray())
        val claims = """{"iss":"https://$TEAM","aud":["$AUD"],""" +
            """"email":"alex@example.com","exp":${now.epochSecond + 3600}}"""
        val payload = b64(claims.toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signWith.private)
            update("$header.$payload".toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$header.$payload.${b64(signature)}"
    }

    private fun unsigned(value: BigInteger): ByteArray =
        value.toByteArray().let { if (it.isNotEmpty() && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun awaitListening() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            runCatching { java.net.Socket("127.0.0.1", port).close() }.onSuccess { return }
            Thread.sleep(25)
        }
        error("the bridge never started listening on $port")
    }

    private companion object {
        const val TEAM = "acme.cloudflareaccess.com"
        const val AUD = "0123456789abcdef"
        const val KID = "kid-1"
        const val ADVERTISED = "wss://bridge.example.com/acp"
    }
}
