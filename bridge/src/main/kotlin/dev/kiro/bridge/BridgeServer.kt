package dev.kiro.bridge

import dev.kiro.core.acp.AcpJson
import dev.kiro.core.acp.JsonRpcCodec
import dev.kiro.core.acp.RpcError
import dev.kiro.core.acp.RpcId
import dev.kiro.core.acp.RpcMalformed
import dev.kiro.core.acp.RpcNotification
import dev.kiro.core.acp.RpcRequest
import dev.kiro.core.acp.RpcResponse
import dev.kiro.core.acp.str
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * The bridge's network face.
 *
 * A thin relay, deliberately. PROTOCOL-FINDINGS §4 established that KAS already
 * does the hard parts this item was originally sized for — multiplexing several
 * clients onto one agent, correlating permissions by `toolCallId` across
 * connections, and re-presenting an outstanding approval to whoever attaches
 * next. Reimplementing any of that here would be building a second, worse copy of
 * something already in the process we are supervising.
 *
 * So this owns exactly four things KAS does not: authentication, transport
 * security, process supervision, and a replay log for a client that was offline.
 */
public class BridgeServer(
    private val config: BridgeConfig,
    private val supervisor: CliSupervisor,
    private val pairing: PairingService,
    private val scope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(BridgeServer::class.java)
    private val sessionLog = SessionLog(config.replayBufferSize)
    private val clients = java.util.concurrent.CopyOnWriteArrayList<ClientConnection>()

    /** Test-only window into the roster. No production caller needs this. */
    internal val clientCount: Int get() = clients.size

    private class ClientConnection(val send: suspend (String) -> Unit)

    public fun start(wait: Boolean = true) {
        config.validate()

        val server = embeddedServer(CIO, port = config.port, host = config.bindAddress) {
            install(WebSockets) {
                // See BridgeConfig.webSocketPingPeriod: without this, a client
                // whose connection goes silently dead is never noticed, and
                // `clients` only ever grows.
                pingPeriod = config.webSocketPingPeriod
                timeout = config.webSocketPongTimeout
            }
            routing { installRoutes() }
        }

        // One fan-out from the agent to every attached client. KAS already keeps
        // per-session subscriber sets, so this stays a broadcast rather than a
        // routing table we would have to keep correct.
        scope.launch {
            supervisor.frames.collect { message ->
                if (message is RpcNotification) recordForReplay(message)
                val encoded = JsonRpcCodec.encode(message)
                clients.forEach { client ->
                    runCatching { client.send(encoded) }
                        .onFailure { log.debug("dropping frame for a closed client") }
                }
            }
        }

        server.start(wait = wait)
    }

    /**
     * Two routes and nothing else. `/pair` is the only unauthenticated one, and
     * everything it can do is bounded by [PairingService].
     */
    private fun Route.installRoutes() {
        // Auth-1's only unauthenticated endpoint, and the one the app hits
        // first. Everything it can do is bounded by PairingService: the
        // code is single-use, short-lived, and rate limited per address.
        post("/pair") {
            val body = runCatching {
                AcpJson.parseToJsonElement(call.receiveText()) as? JsonObject
            }.getOrNull()
            val code = body?.str("code")
            val deviceName = body?.str("deviceName") ?: "Unnamed device"
            val remote = call.request.origin.remoteHost

            if (code == null) {
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                    text = errorBody("A pairing code is required."),
                )
                return@post
            }

            when (val result = pairing.redeem(code, deviceName, remote)) {
                is PairingService.PairResult.Paired -> call.respondText(
                    contentType = ContentType.Application.Json,
                    text = buildJsonObject {
                        put("token", result.token)
                        put("authMode", if (config.apiKey != null) "api_key" else "cli_login")
                    }.toString(),
                )

                // Deliberately distinct messages. F-07 requires error states
                // that say what to do next, and "wrong code" and "too late"
                // have different next steps.
                is PairingService.PairResult.BadCode -> call.respondText(
                    status = HttpStatusCode.Forbidden,
                    contentType = ContentType.Application.Json,
                    text = errorBody("That pairing code is not valid. Check it and try again."),
                )

                is PairingService.PairResult.Expired -> call.respondText(
                    status = HttpStatusCode.Forbidden,
                    contentType = ContentType.Application.Json,
                    text = errorBody(
                        "That pairing code has expired. Run the bridge with --pair " +
                            "to print a new one.",
                    ),
                )

                is PairingService.PairResult.RateLimited -> call.respondText(
                    status = HttpStatusCode.TooManyRequests,
                    contentType = ContentType.Application.Json,
                    text = errorBody(
                        "Too many attempts. Wait ${result.retryAfterSeconds}s and try again.",
                    ),
                )
            }
        }

        webSocket("/acp") {
            val token = call.request.header(HEADER_TOKEN)
                ?: call.request.queryParameters[QUERY_TOKEN]

            if (!pairing.isAuthorised(token)) {
                log.warn("rejecting unauthorised connection")
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not paired"))
                return@webSocket
            }

            val connection = ClientConnection { text -> outgoing.send(Frame.Text(text)) }
            clients.add(connection)
            log.info("client attached ({} total)", clients.size)

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    handleClientFrame(frame.readText(), connection)
                }
            } finally {
                clients.remove(connection)
                log.info("client detached ({} remain)", clients.size)
            }
        }
    }

    private fun recordForReplay(notification: RpcNotification) {
        val sessionId = (notification.params as? JsonObject)?.str("sessionId") ?: return
        sessionLog.record(sessionId, notification)
    }

    /**
     * Client → agent, with one interception.
     *
     * `_bridge/…` methods are ours and must never reach the agent; namespacing
     * them keeps them from being mistaken for harness methods, and keeps a future
     * Kiro method from colliding with one of ours.
     */
    private suspend fun handleClientFrame(text: String, client: ClientConnection) {
        when (val message = JsonRpcCodec.decode(text)) {
            is RpcMalformed -> log.warn("client sent an unparseable frame: {}", message.reason)

            is RpcRequest ->
                if (message.method.startsWith(BRIDGE_NAMESPACE)) {
                    handleBridgeMethod(message, client)
                } else {
                    supervisor.send(message)
                }

            is RpcNotification ->
                if (!message.method.startsWith(BRIDGE_NAMESPACE)) supervisor.send(message)

            // Responses to server-initiated requests -- permission answers -- go
            // straight through. The agent correlates them; we do not interpret them.
            is RpcResponse -> supervisor.send(message)
        }
    }

    private suspend fun handleBridgeMethod(request: RpcRequest, client: ClientConnection) {
        when (request.method) {
            METHOD_RESUME -> {
                val params = request.params as? JsonObject
                val sessionId = params?.str("sessionId")
                if (sessionId == null) {
                    client.respondError(request.id, RpcError.INVALID_PARAMS, "sessionId is required")
                    return
                }
                when (val replay = sessionLog.replay(sessionId, params.str("afterMessageId"))) {
                    is SessionLog.Replay.Truncated ->
                        // Never a silent hole. The app refetches the transcript.
                        client.respondResult(
                            request.id,
                            buildJsonObject {
                                put("truncated", true)
                                put("replayed", 0)
                            },
                        )

                    is SessionLog.Replay.From -> {
                        replay.entries.forEach { entry ->
                            client.send(JsonRpcCodec.encode(entry.notification))
                        }
                        client.respondResult(
                            request.id,
                            buildJsonObject {
                                put("truncated", false)
                                put("replayed", replay.entries.size)
                            },
                        )
                    }
                }
            }

            METHOD_STATUS -> client.respondResult(
                request.id,
                buildJsonObject {
                    put("agentAlive", supervisor.isAlive)
                    put("clients", clients.size)
                    put("sessionsBuffered", sessionLog.sessionCount())
                    // "Signed in as you" and "running under a host API key" are
                    // different trust statements and the settings screen must not
                    // blur them (AUTHENTICATION §3b).
                    put("authMode", if (config.apiKey != null) "api_key" else "cli_login")
                },
            )

            else -> client.respondError(
                request.id,
                RpcError.METHOD_NOT_FOUND,
                "${request.method} is not a bridge method",
            )
        }
    }

    private suspend fun ClientConnection.respondResult(id: RpcId, result: JsonObject) {
        send(JsonRpcCodec.encode(RpcResponse(id, result = result)))
    }

    private suspend fun ClientConnection.respondError(id: RpcId, code: Int, message: String) {
        send(JsonRpcCodec.encode(RpcResponse(id, error = RpcError(code, message))))
    }

    private fun errorBody(message: String): String =
        buildJsonObject { put("error", message) }.toString()

    public companion object {
        /** Ours, not ACP's. Namespaced so the two can never be confused. */
        public const val BRIDGE_NAMESPACE: String = "_bridge/"
        public const val METHOD_RESUME: String = "_bridge/resume"
        public const val METHOD_STATUS: String = "_bridge/status"
        public const val HEADER_TOKEN: String = "X-Kiro-Bridge-Token"
        public const val QUERY_TOKEN: String = "token"
    }
}
