package dev.kiro.bridge

import dev.kiro.core.acp.JsonRpcCodec
import dev.kiro.core.acp.RpcMalformed
import dev.kiro.core.acp.RpcMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.TimeUnit

/**
 * Spawns and supervises one `kiro-cli acp` process, and moves line-delimited
 * JSON-RPC in both directions.
 *
 * The flags are not adjustable and that is the point. `--agent-engine v3` is what
 * makes cloud sessions visible at all; without it the agent is local-only and a
 * client would wrongly conclude the whole architecture is dead
 * (PROTOCOL-FINDINGS §2).
 */
public class CliSupervisor(
    private val config: BridgeConfig,
    private val scope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(CliSupervisor::class.java)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Mutex()

    private val _frames = MutableSharedFlow<RpcMessage>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Everything the agent says, decoded. */
    public val frames: SharedFlow<RpcMessage> = _frames.asSharedFlow()

    public val isAlive: Boolean get() = process?.isAlive == true

    public fun start() {
        check(process == null) { "supervisor already started" }
        config.workingDirectory.mkdirs()

        val builder = ProcessBuilder(
            config.kiroCliPath,
            "acp",
            "--agent-engine", AGENT_ENGINE,
            "--auth-method", AUTH_METHOD,
        ).directory(config.workingDirectory)

        // Explicit, not inherited. See BridgeConfig.childEnvironment.
        builder.environment().clear()
        builder.environment().putAll(config.childEnvironment())

        val started = builder.start()
        process = started
        writer = started.outputWriter()

        scope.launch(Dispatchers.IO) { pumpStdout(started.inputReader()) }
        scope.launch(Dispatchers.IO) { pumpStderr(started.errorReader()) }

        log.info(
            "spawned {} acp (engine {}, auth {}, api key {})",
            config.kiroCliPath,
            AGENT_ENGINE,
            AUTH_METHOD,
            if (config.apiKey != null) "present" else "absent",
        )
    }

    private suspend fun pumpStdout(reader: BufferedReader) {
        reader.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val message = JsonRpcCodec.decode(line)
                if (message is RpcMalformed) {
                    // The CLI occasionally prints non-JSON to stdout. Never fatal.
                    log.debug("dropping unparseable stdout line: {}", message.reason)
                } else {
                    _frames.emit(message)
                }
            }
        }
        log.warn("agent stdout closed")
    }

    private fun pumpStderr(reader: BufferedReader) {
        reader.useLines { lines ->
            for (line in lines) {
                // KAS announces its selected auth mode here, which is the only place
                // it is observable. Worth surfacing at info: it is the difference
                // between "signed in as the user" and "running under a host key".
                if (line.contains("Auth:")) log.info("agent {}", line.trim()) else log.debug(line)
            }
        }
    }

    public suspend fun send(message: RpcMessage) {
        val target = checkNotNull(writer) { "agent not started" }
        val encoded = JsonRpcCodec.encode(message)
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                target.write(encoded)
                target.newLine()
                target.flush()
            }
        }
    }

    public fun stop() {
        val running = process ?: return
        runCatching { writer?.close() }
        running.destroy()
        if (!running.waitFor(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) running.destroyForcibly()
        process = null
        writer = null
    }

    public companion object {
        /**
         * Mandatory. The default engine cannot see cloud sessions at all — the
         * single most expensive thing the original plan got wrong.
         */
        public const val AGENT_ENGINE: String = "v3"

        /**
         * The only value `--auth-method` accepts. Note it does *not* suppress
         * `KIRO_API_KEY`: when that variable is set, the key wins regardless.
         */
        public const val AUTH_METHOD: String = "cli"

        private const val SHUTDOWN_SECONDS = 5L
    }
}
