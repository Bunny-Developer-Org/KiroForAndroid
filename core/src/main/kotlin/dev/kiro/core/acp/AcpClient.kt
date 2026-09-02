package dev.kiro.core.acp

import dev.kiro.core.util.DriftMetrics
import dev.kiro.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Request/response correlation over an [AcpTransport], plus the two things a
 * naive ACP client forgets:
 *
 *  1. **The agent sends requests too.** Permission prompts arrive as
 *     server-initiated requests and must be answered, or the agent blocks forever
 *     the first time it wants to run a command.
 *  2. **An unrecognised frame is normal.** It is logged, counted and dropped —
 *     never fatal (ADR-003 §3).
 *
 * The client owns no session state; that belongs to the gateway above it.
 */
public class AcpClient(
    private val transport: AcpTransport,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.None,
    private val metrics: DriftMetrics = DriftMetrics.None,
    private val defaultTimeout: Duration = 30.seconds,
) {

    private val pending = mutableMapOf<RpcId, CompletableDeferred<RpcResponse>>()
    private val pendingLock = Mutex()
    private var nextId = 1L

    private val _notifications = MutableSharedFlow<RpcNotification>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Agent → client notifications, including every `session/update`. */
    public val notifications: SharedFlow<RpcNotification> = _notifications.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<RpcRequest>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * Agent → client requests. Every one of these needs [respond] called on it.
     *
     * Suspending overflow rather than dropping is deliberate: silently discarding a
     * permission request would hang the agent with no trace.
     */
    public val serverRequests: SharedFlow<RpcRequest> = _serverRequests.asSharedFlow()

    private var pump: Job? = null

    /**
     * Connects the transport and starts pumping [AcpTransport.incoming].
     *
     * Does not return until the transport is connected: only after this
     * suspends through [AcpTransport.connect] and comes back is [request] or
     * [notify] guaranteed to actually reach the wire. A failed connect leaves
     * [pump] unset, so a later call genuinely retries rather than being a no-op.
     */
    public suspend fun start() {
        if (pump != null) return
        transport.connect()
        pump = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                transport.incoming.collect { message -> dispatch(message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The transport doesn't reconnect by design (WebSocketAcpTransport's
                // own doc). failAllPending below is how callers actually learn the
                // connection died; letting this escape would instead crash the whole
                // scope, since nothing joins this fire-and-forget pump job.
                logger.warn("transport closed: ${e.message}")
            } finally {
                failAllPending(TransportClosedException("transport closed"))
            }
        }
    }

    private suspend fun dispatch(message: RpcMessage) {
        when (message) {
            is RpcResponse -> {
                val waiter = pendingLock.withLock { pending.remove(message.id) }
                if (waiter == null) {
                    // A reply to a request we already gave up on. Expected after a
                    // timeout; worth a line, not an error.
                    logger.debug("dropping response for unknown id ${message.id}")
                } else {
                    waiter.complete(message)
                }
            }

            is RpcNotification -> _notifications.emit(message)

            is RpcRequest -> _serverRequests.emit(message)

            is RpcMalformed -> {
                metrics.parseFailure(message.reason)
                logger.warn("dropping malformed frame: ${message.reason}")
            }
        }
    }

    /**
     * Sends a request and waits for its response.
     *
     * @param timeout stream-silence budget for this call. `session/prompt` passes
     *   null: a turn may legitimately run for many minutes, and killing it on a
     *   wall-clock timeout is a bug that only shows up on the tasks people care
     *   most about (ACP-INTEGRATION §8).
     */
    public suspend fun request(
        method: String,
        params: JsonElement? = null,
        timeout: Duration? = defaultTimeout,
    ): JsonElement? {
        val id = pendingLock.withLock { RpcId.Num(nextId++) }
        val waiter = CompletableDeferred<RpcResponse>()
        pendingLock.withLock { pending[id] = waiter }

        try {
            transport.send(RpcRequest(id, method, params))
        } catch (t: Throwable) {
            pendingLock.withLock { pending.remove(id) }
            throw t
        }

        val response = try {
            if (timeout == null) waiter.await() else withTimeout(timeout) { waiter.await() }
        } catch (e: TimeoutCancellationException) {
            pendingLock.withLock { pending.remove(id) }
            throw AcpTimeoutException(method, timeout ?: Duration.ZERO, e)
        }

        response.error?.let { throw AcpRemoteException(it, method) }
        return response.result
    }

    public suspend fun notify(method: String, params: JsonElement? = null) {
        transport.send(RpcNotification(method, params))
    }

    /** Answers a server-initiated request. */
    public suspend fun respond(request: RpcRequest, result: JsonElement) {
        transport.send(RpcResponse(request.id, result = result))
    }

    public suspend fun respondWithError(request: RpcRequest, error: RpcError) {
        transport.send(RpcResponse(request.id, error = error))
    }

    public suspend fun close() {
        pump?.cancel()
        pump = null
        failAllPending(TransportClosedException("client closed"))
        transport.close()
    }

    private suspend fun failAllPending(cause: Throwable) {
        val waiters = pendingLock.withLock {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        waiters.forEach { it.completeExceptionally(cause) }
    }

    /**
     * The handshake.
     *
     * `fs` and `terminal` are advertised as **false** on purpose, and this differs
     * from the editor example in Kiro's own docs. In a cloud session the filesystem
     * and shell live in the sandbox; a phone has neither the user's checkout nor a
     * terminal, so claiming those capabilities would invite the agent to route
     * operations to a client that cannot service them.
     */
    public suspend fun initialize(
        clientName: String = "KiroForAndroid",
        clientVersion: String = "0.1.0",
    ): InitializeResult {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put(
                "clientCapabilities",
                buildJsonObject {
                    put(
                        "fs",
                        buildJsonObject {
                            put("readTextFile", false)
                            put("writeTextFile", false)
                        },
                    )
                    put("terminal", false)
                },
            )
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", clientName)
                    put("version", clientVersion)
                },
            )
        }

        val result = request(AcpMethods.INITIALIZE, params, timeout = HANDSHAKE_TIMEOUT)
        val parsed = InitializeResult.parse(result)
            ?: throw AcpProtocolException("initialize returned an unreadable result")

        if (parsed.protocolVersion != PROTOCOL_VERSION) {
            throw AcpProtocolException(
                "agent speaks ACP ${parsed.protocolVersion}, this client speaks $PROTOCOL_VERSION",
            )
        }
        return parsed
    }

    public companion object {
        public const val PROTOCOL_VERSION: Int = 1

        /**
         * Generous on purpose: the first `initialize` starts KAS, which loads a
         * Node runtime and an MCP subsystem before it answers.
         */
        public val HANDSHAKE_TIMEOUT: Duration = 60.seconds
    }
}

public class AcpTimeoutException(
    public val method: String,
    public val timeout: Duration,
    cause: Throwable? = null,
) : Exception("$method did not respond within $timeout", cause)

public class AcpProtocolException(message: String) : Exception(message)
