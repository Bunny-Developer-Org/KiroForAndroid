package dev.kiro.core.acp

import kotlinx.coroutines.flow.Flow

/**
 * A bidirectional line-delimited JSON-RPC channel.
 *
 * The interface exists so `core/` can be tested against an in-memory fake with no
 * socket, no bridge and no emulator (ADR-003 §2). The real implementation is a
 * WebSocket to the bridge; each text frame is one complete JSON-RPC message, the
 * same framing KAS uses internally.
 */
public interface AcpTransport {

    /**
     * Inbound frames, already decoded. Completes when the peer goes away; fails
     * only on an error the transport itself cannot recover from.
     *
     * A frame that could not be parsed arrives as [RpcMalformed] rather than being
     * dropped here — deciding what to do with it belongs to [AcpClient], which can
     * count it.
     */
    public val incoming: Flow<RpcMessage>

    public suspend fun send(message: RpcMessage)

    public suspend fun close()
}

/** Raised when a call is made on a transport that has already gone away. */
public class TransportClosedException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** A JSON-RPC error returned by the agent, carried as an exception to the caller. */
public class AcpRemoteException(
    public val error: RpcError,
    public val method: String,
) : Exception("$method failed: ${error.message} (code ${error.code})") {

    /**
     * The agent rejected our credentials, or the service behind it did.
     *
     * The `-32000` / `UnauthorizedException` pair was captured deliberately during
     * the A18 probe by driving a remote `session/list` with an invalid key; it is
     * the shape the app's `NotEntitled` / signed-out states have to key off,
     * because F-01 established there is no capability probe that answers this in
     * advance (`whoami` returns identity only).
     */
    public val isAuthFailure: Boolean
        get() = errorType == "UnauthorizedException" ||
            error.message.contains("Authentication required", ignoreCase = true)

    public val errorType: String?
        get() = (error.data as? kotlinx.serialization.json.JsonObject)?.str("errorType")

    public val requestId: String?
        get() = (error.data as? kotlinx.serialization.json.JsonObject)?.str("requestId")
}
