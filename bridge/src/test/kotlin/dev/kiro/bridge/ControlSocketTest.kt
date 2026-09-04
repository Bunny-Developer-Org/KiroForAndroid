package dev.kiro.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ControlSocketTest {

    private lateinit var stateDir: Path
    private lateinit var scope: CoroutineScope
    private lateinit var pairing: PairingService
    private var control: ControlSocket? = null

    @BeforeTest
    fun setUp() {
        // Short by necessity: a Unix socket path is capped at ~108 bytes.
        stateDir = Files.createTempDirectory("kb")
        scope = CoroutineScope(SupervisorJob())
        pairing = PairingService(stateDir.toFile())
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        control?.close()
        scope.cancel()
        runCatching { stateDir.deleteRecursively() }
    }

    private fun start(config: BridgeConfig = BridgeConfig(stateDirectory = stateDir.toFile())): ControlSocket =
        ControlSocket(config, pairing, scope).also {
            it.start()
            control = it
        }

    private fun socketPath(): Path = ControlSocket.pathFor(stateDir)

    /**
     * The entire reason this file exists: a phone can be added to a bridge that is
     * already serving, without restarting it and dropping every attached client.
     */
    @Test
    fun `a code minted over the control socket is one the bridge will redeem`() {
        start()

        val response = ControlSocket.request(socketPath(), ControlSocket.OP_PAIR)

        assertTrue(response.ok)
        val code = assertNotNull(response.code)
        assertEquals("ws://127.0.0.1:8765/acp", response.url)
        assertIs<PairingService.PairResult.Paired>(pairing.redeem(code, "Pixel 8a", "1.2.3.4"))
    }

    /** The advisory is authored by the running bridge so both places say the same thing. */
    @Test
    fun `the response carries the advisory the bridge would have printed itself`() {
        val config = BridgeConfig(stateDirectory = stateDir.toFile())
        start(config)

        val response = ControlSocket.request(socketPath(), ControlSocket.OP_PAIR)

        assertEquals(pairingAdvisory(config), response.advisory)
    }

    /**
     * `PairingService` rewrites `devices.txt` wholesale from its own in-memory map,
     * so a second bridge sharing a state directory silently deletes the first's
     * paired devices on its next write. Refusing to start turns that data loss into
     * something the operator finds out about immediately.
     */
    @Test
    fun `a second bridge on the same state directory is refused rather than allowed to clobber the first`() {
        start()

        val second = ControlSocket(BridgeConfig(stateDirectory = stateDir.toFile()), pairing, scope)
        val failure = assertFailsWith<IllegalStateException> { second.start() }

        assertTrue(stateDir.toString() in failure.message!!, "the message must name the directory at issue")
        assertTrue("--state-dir" in failure.message!!, "the message must say how to fix it")
    }

    /**
     * Regression, found by running the real binary rather than by this suite: the
     * refused bridge still runs its shutdown hook, and an unguarded `close()` there
     * unlinks the *running* bridge's socket. The live bridge then reports itself as
     * "not running" to `bridge pair` until someone restarts it — which is the exact
     * failure this whole mechanism exists to remove.
     */
    @Test
    fun `a bridge that was refused does not delete the running bridge's socket on its way out`() {
        start()

        val second = ControlSocket(BridgeConfig(stateDirectory = stateDir.toFile()), pairing, scope)
        assertFailsWith<IllegalStateException> { second.start() }
        second.close()

        assertTrue(Files.exists(socketPath()), "the running bridge's socket was deleted by a bridge that never bound")
        assertTrue(ControlSocket.isLive(socketPath()), "the running bridge became unreachable")
    }

    /**
     * A crash leaves the socket file behind. Refusing to start on a file that
     * nothing is listening on would strand the bridge until someone deleted it by
     * hand, so a socket that does not answer is cleaned up rather than obeyed.
     */
    @Test
    fun `a stale socket left by a crash is cleaned up on the next start`() {
        ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            .bind(UnixDomainSocketAddress.of(socketPath()))
            .close()
        assertTrue(Files.exists(socketPath()), "the socket file should outlive the channel")

        start()

        assertTrue(ControlSocket.request(socketPath(), ControlSocket.OP_PAIR).ok)
    }

    /** Anyone who can open this socket can pair a phone to the bridge. */
    @Test
    fun `neither the socket nor the state directory is readable by anyone else`() {
        start()

        val shared = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        assertTrue(Files.getPosixFilePermissions(stateDir).none { it in shared }, "state directory is not private")
        assertTrue(Files.getPosixFilePermissions(socketPath()).none { it in shared }, "socket is not private")
    }

    /** Otherwise the next start finds a socket that looks live and refuses to run. */
    @Test
    fun `the socket is removed on shutdown`() {
        start().close()

        assertFalse(Files.exists(socketPath()))
    }

    @Test
    fun `an unknown operation is refused rather than serviced`() {
        start()

        val response = ControlSocket.request(socketPath(), "revokeAll")

        assertFalse(response.ok)
        assertTrue("revokeAll" in response.error!!)
    }

    /**
     * Tolerant parsing (ADR-003 §3) at a new boundary: one bad frame must not take
     * the listener down with it, or a single stray write disables pairing until the
     * bridge is restarted -- exactly what this feature exists to avoid.
     */
    @Test
    fun `garbage does not kill the listener`() {
        start()
        sendRaw("this is not json\n")

        assertTrue(ControlSocket.request(socketPath(), ControlSocket.OP_PAIR).ok, "the listener died on bad input")
    }

    /** A stuck client must not be able to pin memory or hold the accept loop. */
    @Test
    fun `an oversized request is dropped without taking the listener with it`() {
        start()
        sendRaw("x".repeat(64 * 1024))

        assertTrue(ControlSocket.request(socketPath(), ControlSocket.OP_PAIR).ok, "the listener died on a flood")
    }

    /**
     * The nastier half of the same idea, and one the oversized test above cannot
     * reach because it hangs up and hands the server an EOF: a client that connects
     * and simply says nothing.
     *
     * Served inline on a blocking channel, that wedges the accept loop forever —
     * and the damage compounds, because `isLive` reads a timeout as "nothing is
     * listening", so the *next* bridge deletes this one's socket and takes over the
     * state directory. `bridge pair` meanwhile insists no bridge is running while
     * one plainly is.
     */
    @Test
    fun `a client that connects and never speaks does not wedge the listener`() {
        start()

        SocketChannel.open(UnixDomainSocketAddress.of(socketPath())).use { silent ->
            assertTrue(silent.isConnected)

            assertTrue(
                ControlSocket.request(socketPath(), ControlSocket.OP_PAIR).ok,
                "a silent client blocked every later request",
            )
            assertTrue(ControlSocket.isLive(socketPath()), "a silent client made the bridge look dead")
        }
    }

    /** `bridge pair` uses this to tell "no bridge running" from "stale socket". */
    @Test
    fun `isLive answers for a running bridge and not for a dead one`() {
        assertFalse(ControlSocket.isLive(socketPath()), "nothing is listening yet")

        val started = start()
        assertTrue(ControlSocket.isLive(socketPath()))

        started.close()
        assertFalse(ControlSocket.isLive(socketPath()))
    }

    /** Writes and hangs up, ignoring a peer that has already stopped reading. */
    private fun sendRaw(text: String) {
        runCatching {
            SocketChannel.open(UnixDomainSocketAddress.of(socketPath())).use { channel ->
                channel.write(ByteBuffer.wrap(text.toByteArray()))
            }
        }
    }
}
