package dev.kiro.core.session

import dev.kiro.core.acp.AcpClient
import dev.kiro.core.acp.AcpMethods
import dev.kiro.core.acp.AcpTransport
import dev.kiro.core.acp.RpcMessage
import dev.kiro.core.acp.RpcRequest
import dev.kiro.core.acp.RpcResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
}
