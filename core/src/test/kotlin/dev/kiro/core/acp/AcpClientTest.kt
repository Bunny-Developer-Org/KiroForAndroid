package dev.kiro.core.acp

import dev.kiro.core.Fixtures
import dev.kiro.core.util.DriftMetrics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AcpClientTest {

    /** An in-memory transport, so the protocol layer needs no socket to be tested. */
    private class FakeTransport : AcpTransport {
        val inbound = Channel<RpcMessage>(Channel.UNLIMITED)
        val sent = mutableListOf<RpcMessage>()
        var closed = false

        override val incoming: Flow<RpcMessage> = inbound.consumeAsFlow()

        override suspend fun send(message: RpcMessage) {
            if (closed) throw TransportClosedException("closed")
            sent += message
        }

        override suspend fun close() {
            closed = true
            inbound.close()
        }
    }

    @Test
    fun `correlates a response to its request`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        client.start()

        val call = async { client.request("session/list") }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>().single()
        transport.inbound.send(
            RpcResponse(request.id, result = buildJsonObject { put("ok", true) }),
        )
        runCurrent()

        assertEquals("""{"ok":true}""", call.await().toString())
        client.close()
    }

    @Test
    fun `a JSON-RPC error becomes an exception carrying its payload`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        client.start()

        val call = async {
            assertFailsWith<AcpRemoteException> { client.request("session/list") }
        }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>().single()
        transport.inbound.send(
            RpcResponse(
                request.id,
                error = RpcError(
                    -32000,
                    "Authentication required or access denied.",
                    buildJsonObject { put("errorType", "UnauthorizedException") },
                ),
            ),
        )
        runCurrent()

        assertTrue(call.await().isAuthFailure)
        client.close()
    }

    /**
     * The load-bearing tolerance test. A frame we cannot read must be counted and
     * dropped, and the *next* frame must still be delivered — a client that dies on
     * the first unrecognised payload dies on the first Kiro deploy.
     */
    @Test
    fun `a malformed frame is counted and does not stop the session`() = runTest {
        val failures = mutableListOf<String>()
        val transport = FakeTransport()
        val client = AcpClient(
            transport,
            this,
            metrics = object : DriftMetrics {
                override fun parseFailure(reason: String) { failures += reason }
                override fun unknownMethod(method: String) = Unit
                override fun unknownUpdateKind(kind: String) = Unit
            },
        )
        client.start()

        val received = mutableListOf<RpcNotification>()
        val collector = launch { client.notifications.collect { received += it } }
        runCurrent()

        transport.inbound.send(RpcMalformed("{ nonsense", "not valid JSON"))
        transport.inbound.send(RpcNotification("session/update"))
        runCurrent()

        assertEquals(listOf("not valid JSON"), failures)
        assertEquals(listOf("session/update"), received.map { it.method })

        collector.cancel()
        client.close()
    }

    @Test
    fun `a request that never gets an answer times out instead of hanging`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this, defaultTimeout = 1.seconds)
        client.start()

        assertFailsWith<AcpTimeoutException> {
            client.request("session/list", timeout = 1.seconds)
        }
        client.close()
    }

    @Test
    fun `server-initiated requests are surfaced rather than auto-answered`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        client.start()

        val seen = mutableListOf<RpcRequest>()
        val collector = launch { client.serverRequests.collect { seen += it } }
        runCurrent()

        val permission = Fixtures.load("prompt-turn-with-permission.jsonl")
            .map { it.decoded }
            .filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_REQUEST_PERMISSION }

        transport.inbound.send(permission)
        runCurrent()

        assertEquals(1, seen.size)
        // Nothing was sent back automatically. Answering a permission request is a
        // decision only a human makes.
        assertTrue(transport.sent.filterIsInstance<RpcResponse>().isEmpty())

        collector.cancel()
        client.close()
    }

    @Test
    fun `the handshake advertises no filesystem and no terminal`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        client.start()

        val handshake = async { client.initialize() }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>().single()
        assertEquals(AcpMethods.INITIALIZE, request.method)

        val encoded = request.params.toString()
        // Deliberately different from the editor example in Kiro's own docs: in a
        // cloud session the filesystem and shell live in the sandbox, and a phone
        // has neither. Claiming them would invite the agent to route operations to
        // a client that cannot service them.
        assertTrue(encoded.contains(""""readTextFile":false"""))
        assertTrue(encoded.contains(""""writeTextFile":false"""))
        assertTrue(encoded.contains(""""terminal":false"""))

        val fixtureResult = Fixtures.load("initialize-v3.jsonl")
            .map { it.decoded }
            .filterIsInstance<RpcResponse>()
            .first()
        transport.inbound.send(RpcResponse(request.id, result = fixtureResult.result))
        runCurrent()

        assertTrue(handshake.await().supportsCloudSessions)
        client.close()
    }

    @Test
    fun `a protocol version we do not speak fails loudly`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        client.start()

        val handshake = async { assertFailsWith<AcpProtocolException> { client.initialize() } }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>().single()
        transport.inbound.send(
            RpcResponse(request.id, result = buildJsonObject { put("protocolVersion", 99) }),
        )
        runCurrent()

        assertTrue(handshake.await().message!!.contains("99"))
        client.close()
    }
}
