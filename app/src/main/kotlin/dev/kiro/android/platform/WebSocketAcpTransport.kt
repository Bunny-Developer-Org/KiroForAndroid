package dev.kiro.android.platform

import dev.kiro.core.acp.AcpTransport
import dev.kiro.core.acp.JsonRpcCodec
import dev.kiro.core.acp.RpcMessage
import dev.kiro.core.acp.TransportClosedException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The real transport: a WebSocket to the bridge.
 *
 * Each text frame is one complete JSON-RPC message — the same framing KAS uses
 * internally for Web and Mobile, which is what makes the bridge a relay rather
 * than a translator.
 *
 * Note what this class does *not* do: reconnect. Backoff, replay and the
 * foreground service belong to F-15, which owns the lifecycle; a transport that
 * silently reconnects underneath the client would hide exactly the state the UI
 * is required to name.
 */
class WebSocketAcpTransport(
    private val url: String,
    private val token: String,
    private val client: HttpClient = defaultClient(),
) : AcpTransport {

    private var session: WebSocketSession? = null

    override val incoming: Flow<RpcMessage> = flow {
        val active = client.webSocketSession(url) {
            // Header rather than query parameter: a token in a URL ends up in logs
            // and proxy history.
            header("X-Kiro-Bridge-Token", token)
        }
        session = active
        try {
            for (frame in active.incoming) {
                if (frame is Frame.Text) emit(JsonRpcCodec.decode(frame.readText()))
            }
        } finally {
            session = null
        }
    }

    override suspend fun send(message: RpcMessage) {
        val active = session ?: throw TransportClosedException("socket is not open")
        active.send(Frame.Text(JsonRpcCodec.encode(message)))
    }

    override suspend fun close() {
        session?.close()
        session = null
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            install(WebSockets)
        }
    }
}
