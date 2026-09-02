package dev.kiro.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression coverage for the client-roster leak: with several clients
 * attached, disconnecting -- cleanly *or* abruptly, without a WS close
 * handshake -- must bring [BridgeServer.clientCount] back to the number still
 * genuinely connected. A field report had the bridge's "client attached"
 * count climb past 39 with zero "client detached" lines, which is exactly
 * this roster never shrinking.
 *
 * Real production classes throughout: a [BridgeServer] on a real loopback
 * port, a real [PairingService]-issued token, and [java.net.http.HttpClient]
 * WebSocket clients -- no fakes, since the previous investigation into this
 * exact bug died before it ever exercised the real socket layer.
 *
 * [CliSupervisor] is constructed but deliberately never `.start()`ed: this
 * suite is about connection bookkeeping in [BridgeServer], not agent
 * behaviour, and spawning a real `kiro-cli` process buys nothing here.
 */
class BridgeServerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var server: BridgeServer
    private lateinit var pairing: PairingService
    private var port: Int = 0

    @BeforeTest
    fun startServer() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stateDir = Files.createTempDirectory("bridge-server-test").toFile()
        port = freePort()

        val config = BridgeConfig(
            bindAddress = "127.0.0.1",
            port = port,
            stateDirectory = stateDir,
            // Short on purpose: the silently-dead-client test below relies on
            // this to reap within the test's own timeout, and the production
            // defaults (20s / 15s) would make that test needlessly slow.
            webSocketPingPeriod = 300.milliseconds,
            webSocketPongTimeout = 300.milliseconds,
        )
        pairing = PairingService(stateDir)
        val supervisor = CliSupervisor(config, scope)
        server = BridgeServer(config, supervisor, pairing, scope)
        server.start(wait = false)
        awaitListening()
    }

    @AfterTest
    fun stopServer() {
        scope.cancel()
    }

    @Test
    fun `clean disconnects bring the roster back to zero`() {
        val token = mintToken()
        val sockets = List(3) { openClient(token) }
        awaitClientCount(3)

        sockets.forEach { it.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS) }

        awaitClientCount(0)
    }

    @Test
    fun `abrupt disconnects -- no close handshake at all -- also bring the roster back to zero`() {
        val token = mintToken()
        val sockets = List(5) { openClient(token) }
        awaitClientCount(5)

        // No sendClose: this is a socket that dies (crash, dropped network,
        // an abandoned reconnect attempt) rather than one that says goodbye.
        // Ktor still has to notice, or the roster grows without bound exactly
        // as the field report described.
        sockets.forEach { it.abort() }

        awaitClientCount(0)
    }

    @Test
    fun `a client that goes silently dead -- never closes, never answers a ping -- still gets reaped`() {
        // No sendClose, no abort(): the socket is never told to close at all.
        // A crash or an OS-level network drop sends a TCP FIN or RST that the
        // other two tests' `.abort()` also produces, but a stale `adb forward`
        // -- the field report this whole suite exists for -- does neither. The
        // only thing that can still notice this connection is dead is the
        // server's own ping: this listener acknowledges the TCP handshake but
        // never answers a Ping with a Pong, simulating exactly that silence.
        val token = mintToken()
        val silentListener = object : WebSocket.Listener {
            override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
                return null
            }
        }
        openClient(token, silentListener)
        awaitClientCount(1)

        awaitClientCount(0)
    }

    @Test
    fun `a mix of clean and abrupt disconnects still settles at the surviving count`() {
        val token = mintToken()
        val clean = List(2) { openClient(token) }
        val abrupt = List(4) { openClient(token) }
        val survivor = openClient(token)
        awaitClientCount(7)

        clean.forEach { it.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS) }
        abrupt.forEach { it.abort() }

        awaitClientCount(1)
        survivor.abort()
        awaitClientCount(0)
    }

    private fun mintToken(): String {
        val code = pairing.issueCode()
        val result = pairing.redeem(code, "prober", "127.0.0.1")
        check(result is PairingService.PairResult.Paired) { "unexpected pairing result: $result" }
        return result.token
    }

    private fun openClient(token: String, listener: WebSocket.Listener = object : WebSocket.Listener {}): WebSocket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .header("X-Kiro-Bridge-Token", token)
            .buildAsync(URI.create("ws://127.0.0.1:$port/acp"), listener)
            .get(5, TimeUnit.SECONDS)

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun awaitListening() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            runCatching { Socket("127.0.0.1", port).close() }.onSuccess { return }
            Thread.sleep(20)
        }
        error("bridge never started listening on port $port")
    }

    private fun awaitClientCount(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (server.clientCount == expected) return
            Thread.sleep(50)
        }
        assertEquals(expected, server.clientCount)
    }
}
