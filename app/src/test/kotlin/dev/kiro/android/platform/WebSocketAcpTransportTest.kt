package dev.kiro.android.platform

import dev.kiro.core.acp.JsonRpcCodec
import dev.kiro.core.acp.RpcNotification
import dev.kiro.core.acp.TransportClosedException
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Regression coverage for the lazy-handshake bug: [WebSocketAcpTransport.send]
 * used to throw `TransportClosedException("socket is not open")` in production
 * every single time, because the WebSocket handshake only ran inside the
 * `incoming` cold flow's collector, and nothing collected `incoming` before the
 * first `send()`.
 *
 * A fake [dev.kiro.core.acp.AcpTransport] cannot catch a reintroduction of that
 * bug -- the fake has no concept of "handshake happens lazily inside a flow".
 * Only a real socket against a real server pins it, which is what this suite
 * runs: an embedded Ktor CIO server, started once per test on an ephemeral
 * port, playing the bridge's side of `/acp`.
 */
class WebSocketAcpTransportTest {

    private lateinit var server: EmbeddedServer<*, *>
    private var port: Int = 0

    private val receivedFrame = CompletableDeferred<String>()
    private val receivedToken = CompletableDeferred<String?>()

    @BeforeTest
    fun startServer() {
        server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/acp") {
                    receivedToken.complete(call.request.header("X-Kiro-Bridge-Token"))
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            receivedFrame.complete(frame.readText())
                        }
                    }
                }
            }
        }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServer() {
        server.stop(0, 100)
    }

    // Block bodies throughout, deliberately: JUnit4 requires @Test methods to
    // return void, and an expression body (`= runBlocking { ... }`) infers its
    // return type from the block's last statement -- which for the third test
    // would be the Throwable that assertFailsWith returns, not Unit.

    @Test
    fun `connect then send reaches the server without ever collecting incoming`() {
        runBlocking {
            val transport = WebSocketAcpTransport(url = "ws://localhost:$port/acp", token = "test-token")
            transport.connect()

            // Deliberately never touch `transport.incoming` anywhere in this test.
            // Against the pre-fix code, the handshake -- and therefore `session` --
            // was only ever assigned by collecting `incoming`, so `send()` below
            // would fail with TransportClosedException("socket is not open").
            val message = RpcNotification("test/ping", buildJsonObject { put("value", 42) })
            transport.send(message)

            assertEquals(JsonRpcCodec.encode(message), receivedFrame.await())

            transport.close()
        }
    }

    @Test
    fun `the bridge token header reaches the server`() {
        runBlocking {
            val transport = WebSocketAcpTransport(url = "ws://localhost:$port/acp", token = "super-secret-token")
            transport.connect()
            transport.send(RpcNotification("test/ping"))

            assertEquals("super-secret-token", receivedToken.await())

            transport.close()
        }
    }

    @Test
    fun `connect to an unreachable address throws TransportClosedException`() {
        runBlocking {
            // Nothing listens on port 1 from this JVM's perspective, so the OS
            // refuses the connection almost immediately -- well inside the 10s
            // connect timeout.
            val transport = WebSocketAcpTransport(url = "ws://localhost:1/acp", token = "irrelevant")

            // assertFailsWith<TransportClosedException> only passes if that exact
            // type surfaces, which already rules out CancellationException --
            // worth calling out because MainActivity's runCatching and
            // ServiceLocator's `catch (e: Throwable)` both depend on getting a
            // real, named exception here rather than a coroutine cancellation
            // leaking out of withTimeout.
            assertFailsWith<TransportClosedException> {
                transport.connect()
            }
        }
    }
}
