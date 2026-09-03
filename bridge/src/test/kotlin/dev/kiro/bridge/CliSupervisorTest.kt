package dev.kiro.bridge

import dev.kiro.core.acp.RpcMessage
import dev.kiro.core.acp.RpcNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [CliSupervisor] against a real spawned process -- a small shell script standing
 * in for `kiro-cli acp` -- rather than a fake, matching this module's existing
 * preference for exercising the real OS boundary (see the note atop
 * [BridgeServerTest]). Nothing about frame pumping, stdin writing, or shutdown
 * is exercised anywhere else in the suite.
 */
class CliSupervisorTest {

    private lateinit var scope: CoroutineScope
    private lateinit var scriptPath: String

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scriptPath = writeFakeCli()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Prints a greeting frame, then a line that is not JSON at all (must be
     * dropped, never fatal -- ADR-003 §3), then echoes back whatever it reads
     * from stdin as an `echo` notification until stdin closes.
     */
    private fun writeFakeCli(): String {
        val script = Files.createTempFile("fake-kiro-cli", ".sh")
        script.toFile().writeText(
            """
            #!/bin/sh
            echo '{"jsonrpc":"2.0","method":"greeting","params":{"hello":"world"}}'
            echo 'not json at all, should be dropped'
            while IFS= read -r line; do
              echo "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{\"received\":${'$'}line}}"
            done
            """.trimIndent(),
        )
        script.toFile().setExecutable(true)
        return script.toAbsolutePath().toString()
    }

    private fun newSupervisor(): CliSupervisor {
        val workDir = Files.createTempDirectory("cli-supervisor-test").toFile()
        val config = BridgeConfig(kiroCliPath = scriptPath, workingDirectory = workDir)
        return CliSupervisor(config, scope)
    }

    /**
     * Subscribes before returning, via [CoroutineStart.UNDISPATCHED]: the
     * collector must already be registered on [CliSupervisor.frames] -- a
     * zero-replay [kotlinx.coroutines.flow.SharedFlow] -- before the process is
     * started, or the greeting frame races the subscription and is lost.
     */
    private fun collectFrames(supervisor: CliSupervisor): Pair<Job, Channel<RpcMessage>> {
        val channel = Channel<RpcMessage>(Channel.UNLIMITED)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            supervisor.frames.collect { channel.send(it) }
        }
        return job to channel
    }

    @Test
    fun `start spawns the process and streams decoded stdout frames, dropping unparseable lines`() = runBlocking {
        val supervisor = newSupervisor()
        val (collector, frames) = collectFrames(supervisor)

        supervisor.start()
        try {
            assertTrue(supervisor.isAlive)
            val frame = withTimeout(5.seconds) { frames.receive() }
            assertIs<RpcNotification>(frame)
            assertEquals("greeting", frame.method)
        } finally {
            collector.cancel()
            supervisor.stop()
        }
    }

    @Test
    fun `send writes a line to the child's stdin and its reply is decoded from stdout`() = runBlocking {
        val supervisor = newSupervisor()
        val (collector, frames) = collectFrames(supervisor)

        supervisor.start()
        try {
            withTimeout(5.seconds) { frames.receive() } // the greeting; not under test here.

            supervisor.send(RpcNotification(method = "ping"))

            val echoed = withTimeout(5.seconds) { frames.receive() }
            assertIs<RpcNotification>(echoed)
            assertEquals("echo", echoed.method)
            val roundTripped = echoed.params?.jsonObject?.get("received")?.jsonObject?.get("method")?.jsonPrimitive
            assertEquals("ping", roundTripped?.content)
        } finally {
            collector.cancel()
            supervisor.stop()
        }
    }

    @Test
    fun `stop terminates the process`() = runBlocking {
        val supervisor = newSupervisor()
        val (collector, frames) = collectFrames(supervisor)
        supervisor.start()
        withTimeout(5.seconds) { frames.receive() }
        collector.cancel()

        supervisor.stop()

        assertFalse(supervisor.isAlive)
    }

    @Test
    fun `start refuses to run twice`() = runBlocking {
        val supervisor = newSupervisor()
        supervisor.start()
        try {
            assertFailsWith<IllegalStateException> { supervisor.start() }
            Unit
        } finally {
            supervisor.stop()
        }
    }

    @Test
    fun `send before start fails instead of hanging`() = runBlocking {
        val supervisor = newSupervisor()

        assertFailsWith<IllegalStateException> { supervisor.send(RpcNotification(method = "ping")) }
        Unit
    }
}
