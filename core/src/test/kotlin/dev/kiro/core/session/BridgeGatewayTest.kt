package dev.kiro.core.session

import dev.kiro.core.acp.AcpClient
import dev.kiro.core.acp.AcpMethods
import dev.kiro.core.acp.AcpTransport
import dev.kiro.core.acp.RpcMessage
import dev.kiro.core.acp.RpcNotification
import dev.kiro.core.acp.RpcRequest
import dev.kiro.core.acp.RpcResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeGatewayTest {

    /** An in-memory transport, same shape as [dev.kiro.core.acp.AcpClientTest]'s. */
    private class FakeTransport : AcpTransport {
        val inbound = Channel<RpcMessage>(Channel.UNLIMITED)
        val sent = mutableListOf<RpcMessage>()

        override val incoming: Flow<RpcMessage> = inbound.consumeAsFlow()

        override suspend fun connect() = Unit

        override suspend fun send(message: RpcMessage) {
            sent += message
        }

        override suspend fun close() {
            inbound.close()
        }
    }

    /**
     * Regression test for the live-protocol break PROTOCOL-FINDINGS §4c documents:
     * `session/new` used to accept `cwd: ""` and now rejects it with `Invalid
     * params: cwd must be an absolute path`. Pins that `createSession` sends
     * something absolute instead, without pinning the exact placeholder chosen —
     * §4c found the server accepts any absolute path identically.
     */
    @Test
    fun `createSession sends an absolute cwd rather than an empty string`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(client, this)
        client.start()

        val call = async {
            gateway.createSession(
                CreateSessionRequest(repositories = emptyList(), firstPrompt = "hi"),
            )
        }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_NEW }
        val params = request.params as JsonObject
        val cwd = (params["cwd"] as JsonPrimitive).content

        assertTrue(cwd.isNotEmpty(), "cwd must not be empty -- the live server rejects that")
        assertTrue(cwd.startsWith("/"), "cwd must be an absolute path, was \"$cwd\"")

        transport.inbound.send(
            RpcResponse(request.id, result = buildJsonObject { put("sessionId", "s-1") }),
        )
        runCurrent()

        assertEquals("s-1", call.await().id)
        client.close()
    }

    /** [BridgeGateway.deleteSession] sends `namespace.sessionDelete`, per F-10. */
    @Test
    fun `deleteSession sends the session id to the extension delete method`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(client, this)
        client.start()

        val call = async { gateway.deleteSession("s-42") }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == "_kiro/session/delete" }
        val params = request.params as JsonObject
        assertEquals("s-42", (params["sessionId"] as JsonPrimitive).content)

        transport.inbound.send(RpcResponse(request.id, result = buildJsonObject { }))
        runCurrent()

        call.await()
        client.close()
    }

    /**
     * The roster pushes itself over `_kiro/sessions/changed` (F-10): a session
     * deleted from another client must still disappear here without a poll.
     */
    @Test
    fun `a sessions changed notification with deleted ids surfaces as a RosterChange`() = runTest {
        val transport = FakeTransport()
        // `backgroundScope`, not `this`: once connected, BridgeGateway keeps two
        // notification pumps running for the life of the connection -- they never
        // complete on their own, so runTest needs them on the scope it auto-cancels
        // at the end of the test rather than the one it expects to drain.
        val client = AcpClient(transport, backgroundScope)
        val gateway = BridgeGateway(client, backgroundScope)

        val connectJob = async { gateway.connect() }
        runCurrent()

        val initRequest = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.INITIALIZE }
        transport.inbound.send(
            RpcResponse(initRequest.id, result = buildJsonObject { put("protocolVersion", 1) }),
        )
        runCurrent()
        connectJob.await()

        val rosterChanges = mutableListOf<RosterChange>()
        backgroundScope.launch { gateway.rosterChanges.toList(rosterChanges) }
        runCurrent()

        transport.inbound.send(
            RpcNotification(
                method = "_kiro/sessions/changed",
                params = buildJsonObject {
                    put("upserted", buildJsonArray { })
                    put("deleted", buildJsonArray { add(JsonPrimitive("s-1")) })
                },
            ),
        )
        runCurrent()

        assertEquals(1, rosterChanges.size)
        assertEquals(listOf("s-1"), rosterChanges.single().deleted)
        assertTrue(rosterChanges.single().upserted.isEmpty())
    }
}
