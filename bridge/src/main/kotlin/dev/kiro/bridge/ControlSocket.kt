package dev.kiro.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * A door onto the running bridge that only this host can open.
 *
 * It exists so that adding a phone stops meaning "restart the bridge". Minting a
 * pairing code has to happen *inside* the process that will redeem it, so a second
 * invocation of the binary cannot do it alone -- it has to ask this.
 *
 * **Why a filesystem socket rather than an HTTP route**, which is the least
 * obvious decision in this file and must not be "simplified" away: behind
 * Cloudflare Tunnel, `cloudflared` connects to `http://127.0.0.1:8765`, so every
 * tunnelled request arrives at the server with
 * `call.request.origin.remoteHost == "127.0.0.1"` (see `BridgeServer`). A
 * "loopback only" check on an HTTP route is therefore satisfied by the entire
 * public internet, and a route that mints pairing codes behind such a check is an
 * open pairing endpoint. Filesystem permissions are the one boundary a tunnel does
 * not flatten.
 *
 * Anyone who can open this socket can pair a phone to this bridge. That is
 * strictly less than they already have as the bridge's own user -- who can read
 * `devices.txt`, restart the process, or read the API key out of its environment
 * file -- so it grants no new capability, but it is the reason for the 0700
 * directory and the 0600 socket.
 */
internal class ControlSocket(
    private val config: BridgeConfig,
    private val pairing: PairingService,
    private val scope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(ControlSocket::class.java)
    private val path = pathFor(config.stateDirectory.toPath())
    private var server: ServerSocketChannel? = null

    /**
     * @throws IllegalStateException when another bridge already owns this state
     *   directory. That is fatal on purpose: `PairingService` rewrites
     *   `devices.txt` wholesale from its own in-memory map, so a second bridge
     *   sharing the directory silently deletes the first's paired devices on its
     *   next write. A refusal to start beats discovering that afterwards.
     */
    fun start() {
        if (Files.exists(path)) {
            check(!isLive(path)) {
                "another kiro-bridge is already running with --state-dir ${config.stateDirectory}. " +
                    "Two bridges sharing one state directory overwrite each other's paired-device " +
                    "list. Give the second one its own --state-dir."
            }
            // Nothing answered, so this is a socket a crash left behind.
            log.info("removing a stale control socket at {}", path)
            Files.deleteIfExists(path)
        }

        Files.createDirectories(path.parent)
        restrictToOwner(path.parent, "rwx------")

        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            .bind(UnixDomainSocketAddress.of(path))

        // Best effort, and the directory above is the control that actually holds:
        // Linux checks a socket's own mode on connect, some other systems do not,
        // and there is no window between bind() and connectability in which to
        // chmod. A 0700 directory is enforced everywhere.
        restrictToOwner(path, "rw-------")
        path.toFile().deleteOnExit()
        acceptLoop()
        log.info("control socket listening at {} -- run `bridge pair` to add a phone", path)
    }

    /**
     * Unlinks only a socket this instance actually bound.
     *
     * The guard is load-bearing, not defensive tidiness. A second bridge that
     * refused to start because another one owns this directory still runs its
     * shutdown hook, and without the guard it deletes the *running* bridge's
     * socket on the way out -- leaving a live bridge that `bridge pair` reports as
     * "not running" until someone restarts it. That is the exact failure this
     * whole mechanism exists to remove, reintroduced by a tidy-up.
     */
    fun close() {
        val bound = server ?: return
        server = null
        runCatching { bound.close() }
        runCatching { Files.deleteIfExists(path) }
    }

    private fun acceptLoop() {
        val channel = server ?: return
        scope.launch(Dispatchers.IO) {
            // Termination lives in the condition: close() shuts the channel, and
            // the accept below throws on the way out.
            while (isActive && channel.isOpen) {
                val accepted = try {
                    channel.accept()
                } catch (e: IOException) {
                    // A closed channel is the ordinary ending and the condition
                    // above catches it. Anything else is transient -- a momentary
                    // fd shortage, an aborted connection -- and must not stop us
                    // listening: a bound socket that never answers is reported by
                    // `bridge pair` as "no bridge is running", which is a lie, and
                    // one no amount of retrying by the operator will fix.
                    if (channel.isOpen) {
                        log.debug("control socket accept failed, still listening", e)
                        delay(ACCEPT_RETRY_MILLIS)
                    }
                    null
                }
                accepted?.let { serve(it) }
            }
        }
    }

    /**
     * One connection, off the accept loop, under a deadline.
     *
     * Both halves matter. Serving inline would let a single client that connects
     * and never sends a newline block the loop **forever** -- and because
     * [isLive] reads a timeout as "nothing is listening", a wedged bridge would
     * then let the next one delete its socket and take over the state directory,
     * which is precisely the `devices.txt` clobbering [start] refuses to allow.
     *
     * A blocking channel has no read timeout, so the deadline is a watchdog that
     * closes the channel; that is what makes the blocked read throw.
     */
    private fun CoroutineScope.serve(channel: SocketChannel) {
        launch(Dispatchers.IO) {
            val watchdog = launch {
                delay(HANDLE_TIMEOUT_MILLIS)
                log.debug("closing a control connection that never completed a request")
                runCatching { channel.close() }
            }
            try {
                channel.use { runCatching { handle(it) }.onFailure { e -> log.debug("control request failed", e) } }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private fun handle(channel: SocketChannel) {
        val line = readLine(channel) ?: return // oversized or empty: hang up, say nothing
        val request = runCatching { ControlJson.decodeFromString<ControlRequest>(line) }.getOrNull()
        val response = when (request?.op) {
            OP_PING -> ControlResponse(ok = true, op = OP_PING)
            OP_PAIR -> pairResponse()
            null -> ControlResponse(ok = false, error = "not a control request")
            else -> ControlResponse(ok = false, error = "${request.op} is not a control operation")
        }
        channel.write(ByteBuffer.wrap((ControlJson.encodeToString(response) + "\n").toByteArray()))
    }

    /**
     * The advertised URL and the advisory are authored *here*, in the process that
     * knows its own argv, and sent back for the caller to print verbatim. A
     * `bridge pair` invocation has no `--public-url` of its own and must never have
     * to be given one twice.
     */
    private fun pairResponse() = ControlResponse(
        ok = true,
        op = OP_PAIR,
        code = pairing.issueCode(PairingService.CodeSource.TERMINAL),
        url = config.advertisedUrl(),
        ttlSeconds = PairingService.CODE_TTL_SECONDS,
        advisory = pairingAdvisory(config),
    )

    @Serializable
    data class ControlRequest(val v: Int = PROTOCOL_VERSION, val op: String)

    @Serializable
    data class ControlResponse(
        val v: Int = PROTOCOL_VERSION,
        val ok: Boolean,
        val op: String? = null,
        val code: String? = null,
        val url: String? = null,
        val ttlSeconds: Long? = null,
        val advisory: String? = null,
        val error: String? = null,
    )

    companion object {
        const val PROTOCOL_VERSION: Int = 1
        const val OP_PAIR: String = "pair"
        const val OP_PING: String = "ping"
        const val SOCKET_NAME: String = "control.sock"

        /** A stuck client must not be able to pin memory or hold the accept loop. */
        private const val MAX_REQUEST_BYTES = 4096
        private const val READ_CHUNK_BYTES = 256
        private const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        /** How long one connection may take to send a complete request line. */
        private const val HANDLE_TIMEOUT_MILLIS = 5_000L

        /** Keeps a repeating accept failure from becoming a hot loop. */
        private const val ACCEPT_RETRY_MILLIS = 100L

        /** Same `encodeDefaults` reasoning as PairingPayload: `v` and `ok` must be sent. */
        private val ControlJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun pathFor(stateDirectory: Path): Path = stateDirectory.resolve(SOCKET_NAME)

        /** True when something on the other end answers, i.e. a bridge is really running. */
        fun isLive(path: Path): Boolean = runCatching { request(path, OP_PING).ok }.getOrDefault(false)

        /**
         * Asks the running bridge for something, from a separate process.
         *
         * The exchange runs on a daemon thread with a join deadline because a
         * blocking [SocketChannel] has no read timeout of its own; closing the
         * channel from here is what unblocks a stuck read. Do not "simplify" this
         * into a bare blocking read -- that turns a wedged bridge into a `bridge
         * pair` that never returns.
         */
        fun request(path: Path, op: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ControlResponse {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).use { channel ->
                var reply: String? = null
                var failure: Throwable? = null
                val worker = Thread {
                    runCatching {
                        val line = ControlJson.encodeToString(ControlRequest(op = op)) + "\n"
                        channel.write(ByteBuffer.wrap(line.toByteArray()))
                        reply = readLine(channel)
                    }.onFailure { failure = it }
                }
                worker.isDaemon = true
                worker.start()
                worker.join(timeoutMillis)
                if (worker.isAlive) {
                    channel.close()
                    error("the bridge at $path did not answer within ${timeoutMillis}ms")
                }
                failure?.let { throw it }
                val body = reply ?: error("the bridge at $path closed without answering")
                return ControlJson.decodeFromString(body)
            }
        }

        private fun readLine(channel: SocketChannel): String? {
            val out = ByteArrayOutputStream()
            val chunk = ByteBuffer.allocate(READ_CHUNK_BYTES)
            while (true) {
                chunk.clear()
                val read = channel.read(chunk)
                if (read <= 0) break
                chunk.flip()
                repeat(read) {
                    val byte = chunk.get()
                    if (byte == '\n'.code.toByte()) return out.toString(Charsets.UTF_8)
                    out.write(byte.toInt())
                }
                if (out.size() > MAX_REQUEST_BYTES) return null
            }
            return out.toString(Charsets.UTF_8).ifEmpty { null }
        }

        private fun restrictToOwner(target: Path, mode: String) {
            // Throws on a filesystem without POSIX permissions; the bridge must not
            // die for that, so the failure is logged by the caller's absence of one.
            runCatching { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(mode)) }
        }
    }
}
