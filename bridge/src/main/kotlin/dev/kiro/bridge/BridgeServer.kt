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
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
import java.time.Instant

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
public class BridgeServer internal constructor(
    private val config: BridgeConfig,
    private val supervisor: CliSupervisor,
    private val pairing: PairingService,
    private val scope: CoroutineScope,
    // Injected so tests can drive rotation with a fake clock and a fake JWKS; both
    // carry their own clock. `AccessVerifier` and `QrPageBudget` are internal (as
    // `ControlSocket` is), which is why this constructor is too and the public one
    // below delegates to it.
    private val verifier: AccessVerifier?,
    private val budget: QrPageBudget,
) {

    public constructor(
        config: BridgeConfig,
        supervisor: CliSupervisor,
        pairing: PairingService,
        scope: CoroutineScope,
    ) : this(
        config,
        supervisor,
        pairing,
        scope,
        verifier = config.accessTeamDomain?.let { domain ->
            config.accessAudience?.let { aud -> AccessVerifier(domain, aud, Instant::now) }
        },
        budget = QrPageBudget(Instant::now),
    )

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
            install(StatusPages) {
                // Ktor's stock 404 has an empty body, which tells someone who
                // mistyped a path nothing at all -- including whether they even
                // reached the bridge or something in front of it.
                status(HttpStatusCode.NotFound) { call, status ->
                    call.respondText(
                        status = status,
                        contentType = ContentType.Text.Plain,
                        text = "Not a route on this bridge. GET / says what this is.\n",
                    )
                }
            }
            routing { installRoutes() }
        }

        // Fire and forget, deliberately: a typo'd team domain should be visible in
        // the log at startup rather than discovered when somebody opens /qr, but the
        // bridge's boot must not depend on Cloudflare's being reachable -- the same
        // rule the --public-url log line follows.
        verifier?.let { scope.launch { it.warmUp() } }

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
     * Three routes and nothing else. `/` and `/pair` are the unauthenticated ones,
     * and everything `/pair` can do is bounded by [PairingService].
     */
    private fun Route.installRoutes() {
        installQrRoutes(config, pairing, verifier, budget)

        // Reachable by anyone who can resolve the hostname, so it says only what
        // the hostname already gives away. Someone who lands here deserves to know
        // what the thing is; nobody needs to learn from it which Kiro account it
        // holds, what it is working on, or how many phones have paired with it.
        get("/") {
            call.respondText(ROOT_PAGE, ContentType.Text.Plain)
        }

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
                        "That pairing code has expired. Run `kiro-bridge pair` on the " +
                            "machine running the bridge to print a new one.",
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
        /**
         * What a browser gets at `/`.
         *
         * Every line here is public. It names the project, because someone who
         * finds this hostname should be able to work out what it is rather than
         * guess, and it names the other two routes, because they are discoverable in a
         * public repository anyway. It states no operational fact -- not the
         * account, not the workload, not the paired devices, not even whether the
         * agent is currently up, which would make this a free availability oracle.
         */
        public val ROOT_PAGE: String = """
            kiro-bridge

            The host-side relay for KiroForAndroid, an unofficial Android client for
            Kiro cloud sessions:

              https://github.com/Bunny-Developer-Org/KiroForAndroid

            Three routes past this page, and nothing else:

              GET  /qr     a pairing page, if this bridge has been given a
                           Cloudflare Access application to sit behind
              POST /pair   exchange a single-use pairing code for a device token
              GET  /acp    the agent WebSocket -- requires that token

            A pairing code is printed by the bridge on the machine that runs it, as
            text and as a QR. If that machine is yours, `kiro-bridge pair` prints a
            fresh one without restarting anything.

            This page is deliberately incurious about its own bridge: it will not
            tell you which Kiro account is signed in here, what it is working on, or
            who has paired with it.

        """.trimIndent()

        /** Ours, not ACP's. Namespaced so the two can never be confused. */
        public const val BRIDGE_NAMESPACE: String = "_bridge/"
        public const val METHOD_RESUME: String = "_bridge/resume"
        public const val METHOD_STATUS: String = "_bridge/status"
        public const val HEADER_TOKEN: String = "X-Kiro-Bridge-Token"
        public const val QUERY_TOKEN: String = "token"
    }
}
