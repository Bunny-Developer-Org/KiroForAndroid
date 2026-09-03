package dev.kiro.core.session

import dev.kiro.core.acp.AcpClient
import dev.kiro.core.acp.AcpMethods
import dev.kiro.core.acp.AcpTransport
import dev.kiro.core.acp.RpcError
import dev.kiro.core.acp.RpcMessage
import dev.kiro.core.acp.RpcNotification
import dev.kiro.core.acp.RpcRequest
import dev.kiro.core.acp.RpcResponse
import dev.kiro.core.auth.PairedBridge
import dev.kiro.core.model.ModelSelection
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
import kotlin.test.assertFalse
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

    /**
     * `session/set_model` is the ACP-standard spelling and KAS does not implement
     * it — its dispatcher answers method-not-found when the agent has no
     * `unstable_setSessionModel`, which `KiroAgent` does not (PROTOCOL-FINDINGS
     * §4d). The verb that works is `session/set_config_option`.
     */
    @Test
    fun `setModel sends set_config_option with the model config id`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(client, this)
        client.start()

        val call = async { gateway.setModel("s-1", "claude-opus-5") }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_SET_CONFIG_OPTION }
        val params = request.params as JsonObject
        assertEquals("s-1", (params["sessionId"] as JsonPrimitive).content)
        assertEquals("model", (params["configId"] as JsonPrimitive).content)
        assertEquals("claude-opus-5", (params["value"] as JsonPrimitive).content)

        transport.inbound.send(RpcResponse(request.id, result = buildJsonObject { }))
        runCurrent()
        call.await()
        client.close()
    }

    /**
     * The gateway reports what the agent confirmed, not what was asked for: the
     * `set_config_option` response carries the authoritative set, so a coerced or
     * refused value must not be rendered as the current model.
     */
    @Test
    fun `setModel takes the current model from the response rather than the request`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(client, this)
        client.start()

        val call = async { gateway.setModel("s-1", "claude-opus-5") }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_SET_CONFIG_OPTION }
        transport.inbound.send(RpcResponse(request.id, result = modelConfigResult("auto")))
        runCurrent()
        call.await()

        assertEquals("auto", gateway.modelsFor("s-1").currentId)
        assertEquals(listOf("auto", "claude-opus-5"), gateway.modelsFor("s-1").available.map { it.id })
        client.close()
    }

    /** An agent that knows neither verb must surface an error, not a silent no-op. */
    @Test
    fun `setModel falls back to session set_model when set_config_option is unknown`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(client, this)
        client.start()

        val call = async { gateway.setModel("s-1", "claude-opus-5") }
        runCurrent()

        val first = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_SET_CONFIG_OPTION }
        transport.inbound.send(
            RpcResponse(
                first.id,
                error = RpcError(RpcError.METHOD_NOT_FOUND, "session/set_config_option not found"),
            ),
        )
        runCurrent()

        val fallback = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_SET_MODEL }
        assertEquals(
            "claude-opus-5",
            ((fallback.params as JsonObject)["modelId"] as JsonPrimitive).content,
        )
        transport.inbound.send(RpcResponse(fallback.id, result = buildJsonObject { }))
        runCurrent()
        call.await()
        client.close()
    }

    /**
     * The cloud path in full: `session/new` answers with a bare `sessionId` and no
     * config options at all, and the model only becomes known when the sandbox
     * pushes `config_option_update` some time later.
     */
    @Test
    fun `a cloud session's model is unknown until config_option_update arrives`() = runTest {
        val transport = FakeTransport()
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

        assertEquals(ModelSelection.Unknown, gateway.modelsFor("s-1"))

        transport.inbound.send(
            RpcNotification(
                method = AcpMethods.SESSION_UPDATE,
                params = buildJsonObject {
                    put("sessionId", "s-1")
                    put(
                        "update",
                        buildJsonObject {
                            put("sessionUpdate", "config_option_update")
                            modelConfigResult("claude-opus-5").forEach { (k, v) -> put(k, v) }
                        },
                    )
                },
            ),
        )
        runCurrent()

        assertEquals("claude-opus-5", gateway.modelsFor("s-1").currentId)
    }

    /**
     * The regression AUTHENTICATION §3b's 2026-09-03 contradiction opened: this
     * message used to lead with "needs a Pro plan or higher" and was shown to a
     * Pro+ account whose bridge was running under a `KIRO_API_KEY`. When the auth
     * mode is known to be a host key, the copy must name that first.
     */
    @Test
    fun `an unauthorized create names the bridge's API key before the plan`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(
            client,
            this,
            bridgeAuthMode = PairedBridge.AuthMode.API_KEY,
        )
        client.start()

        val call = async {
            runCatching {
                gateway.createSession(CreateSessionRequest(repositories = emptyList(), firstPrompt = "hi"))
            }
        }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_NEW }
        transport.inbound.send(
            RpcResponse(
                request.id,
                error = RpcError(
                    code = -32000,
                    message = "Authentication required or access denied. (Request ID: abc)",
                    data = buildJsonObject {
                        put("errorType", "UnauthorizedException")
                        put("faultKind", "serviceRejection")
                        put("requestId", "abc")
                    },
                ),
            ),
        )
        runCurrent()

        val failure = call.await().exceptionOrNull()
        val message = (failure as NotEntitledException).message.orEmpty()

        assertTrue(message.contains("unauthorized"), message)
        assertTrue(message.contains("API key"), message)
        assertTrue(
            message.indexOf("API key") < message.indexOf("Pro plan"),
            "the plan must not be the headline cause: $message",
        )
        assertTrue(message.contains("abc"), "the server's request id is the only handle on it")
        client.close()
    }

    /** Without a known auth mode the message must still not assert the plan is wrong. */
    @Test
    fun `an unauthorized create with an unknown auth mode blames neither cause outright`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(client, this)
        client.start()

        val call = async {
            runCatching {
                gateway.createSession(CreateSessionRequest(repositories = emptyList(), firstPrompt = "hi"))
            }
        }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_NEW }
        transport.inbound.send(
            RpcResponse(
                request.id,
                error = RpcError(
                    code = -32000,
                    message = "Authentication required or access denied.",
                    data = buildJsonObject { put("errorType", "UnauthorizedException") },
                ),
            ),
        )
        runCurrent()

        val message = (call.await().exceptionOrNull() as NotEntitledException).message.orEmpty()
        assertFalse(
            message.startsWith("This Kiro account cannot create cloud sessions"),
            "the sentence that cost the debugging time must not come back",
        )
        assertTrue(message.contains("KIRO_API_KEY"), message)
        assertTrue(message.contains("entitlement"), message)
        client.close()
    }

    /**
     * KAS selects the mode from `_meta.kiro.modeId`; nothing reads a top-level
     * `agentMode` (PROTOCOL-FINDINGS §4d). Sending only the latter, as this client
     * did, meant every cloud session silently ran `vibe`.
     */
    @Test
    fun `createSession puts the mode where the agent actually reads it`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(client, this)
        client.start()

        val call = async {
            gateway.createSession(
                CreateSessionRequest(repositories = emptyList(), firstPrompt = "hi", modeId = "plan"),
            )
        }
        runCurrent()

        val request = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_NEW }
        val kiro = ((request.params as JsonObject)["_meta"] as JsonObject)["kiro"] as JsonObject
        assertEquals("plan", (kiro["modeId"] as JsonPrimitive).content)

        transport.inbound.send(
            RpcResponse(request.id, result = buildJsonObject { put("sessionId", "s-1") }),
        )
        runCurrent()
        call.await()
        client.close()
    }

    /**
     * A cloud create drops `_meta.kiro.modelId`, so a requested model has to be a
     * second call — and one that cannot strand the session it just made.
     */
    @Test
    fun `a requested model is applied after the create and does not fail it`() = runTest {
        val transport = FakeTransport()
        val client = AcpClient(transport, this)
        val gateway = BridgeGateway(client, this)
        client.start()

        val call = async {
            gateway.createSession(
                CreateSessionRequest(
                    repositories = emptyList(),
                    firstPrompt = "hi",
                    modelId = "claude-opus-5",
                ),
            )
        }
        runCurrent()

        val create = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_NEW }
        transport.inbound.send(
            RpcResponse(create.id, result = buildJsonObject { put("sessionId", "s-1") }),
        )
        runCurrent()

        val setModel = transport.sent.filterIsInstance<RpcRequest>()
            .single { it.method == AcpMethods.SESSION_SET_CONFIG_OPTION }
        // Refused: the session still has to come back.
        transport.inbound.send(
            RpcResponse(setModel.id, error = RpcError(-32000, "no such model")),
        )
        runCurrent()

        assertEquals("s-1", call.await().id)
        client.close()
    }

    /** A `model` select with two choices, in the shape KAS sends. */
    private fun modelConfigResult(currentValue: String): JsonObject = buildJsonObject {
        put(
            "configOptions",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", "model")
                        put("name", "Model")
                        put("category", "model")
                        put("currentValue", currentValue)
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject { put("value", "auto"); put("name", "Auto") })
                                add(
                                    buildJsonObject {
                                        put("value", "claude-opus-5")
                                        put("name", "Claude Opus 5")
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }
}
