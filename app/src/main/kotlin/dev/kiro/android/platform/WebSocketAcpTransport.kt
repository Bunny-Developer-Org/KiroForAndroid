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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 *
 * [connect] must complete before [send] will work — the handshake is a distinct,
 * awaitable lifecycle event, kept separate from [incoming] on purpose. [incoming]
 * is just a stream once connected; folding the handshake into it would make the
 * connection a side effect of whoever happens to start collecting, rather than
 * something callers can wait on directly.
 */
class WebSocketAcpTransport(
    private val url: String,
    private val token: String,
    private val client: HttpClient = defaultClient(),
    private val connectTimeout: Duration = 10.seconds,
) : AcpTransport {

    private val mutex = Mutex()
    private var session: WebSocketSession? = null

    override suspend fun connect() {
        mutex.withLock {
            if (session != null) return
            session = try {
                withTimeout(connectTimeout) {
                    client.webSocketSession(url) {
                        // Header rather than query parameter: a token in a URL ends up
                        // in logs and proxy history.
                        header(HEADER_TOKEN, token)
                    }
                }
            } catch (t: Throwable) {
                throw TransportClosedException("failed to connect: ${t.message}", t)
            }
        }
    }

    override val incoming: Flow<RpcMessage> = flow {
        val active = mutex.withLock { session }
            ?: throw TransportClosedException("connect() has not run")
        // No `finally { session = null }` here: if the pump collecting this flow
        // gets cancelled by AcpClient.close() before transport.close() runs, a
        // finally here would null the field first and the real socket would never
        // actually get closed — leaking the connection. close() below is the sole
        // owner of tearing down `session`.
        for (frame in active.incoming) {
            if (frame is Frame.Text) emit(JsonRpcCodec.decode(frame.readText()))
        }
    }

    override suspend fun send(message: RpcMessage) {
        val active = mutex.withLock { session } ?: throw TransportClosedException("socket is not open")
        try {
            active.send(Frame.Text(JsonRpcCodec.encode(message)))
        } catch (t: Throwable) {
            throw TransportClosedException("failed to send: ${t.message}", t)
        }
    }

    override suspend fun close() {
        val active = mutex.withLock {
            val current = session
            session = null
            current
        }
        active?.close()
    }

    companion object {
        private const val HEADER_TOKEN = "X-Kiro-Bridge-Token"

        /**
         * Matches the bridge's own `webSocketPingPeriod` (BridgeConfig, 20s), so
         * both ends probe on the same cadence and neither has to guess.
         */
        private const val PING_INTERVAL_SECONDS = 20L

        fun defaultClient(): HttpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
            engine {
                // Keepalive is load-bearing, not tuning. The bridge is reached
                // through a Cloudflare tunnel (docs/HOSTING.md) and the phone's
                // carrier NAT, both of which drop an idle connection within a
                // couple of minutes, and Doze can freeze the socket outright with
                // the screen off. The ping does two jobs: it keeps the path warm,
                // and -- since OkHttp fails a socket whose ping goes unanswered --
                // it is what makes a half-open connection *detectable* at all.
                // Without it the read side notices nothing and the first symptom
                // is "failed to send: Software caused connection abort" the next
                // time the user taps something.
                //
                // Set on the OkHttp client rather than through `install(WebSockets)
                // { pingIntervalMillis }`: Ktor 3.5.2's OkHttp engine never reads
                // the plugin's value (verified -- `OkHttpEngine`'s bytecode has no
                // reference to it), so configuring it there would be silently inert.
                config { pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS) }
            }
            install(WebSockets)
        }
    }
}
