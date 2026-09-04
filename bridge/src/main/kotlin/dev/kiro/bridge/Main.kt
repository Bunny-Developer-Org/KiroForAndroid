package dev.kiro.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AccessDeniedException
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("dev.kiro.bridge.Main")

public fun main(args: Array<String>) {
    if (args.contains("--help") || args.contains("-h")) {
        println(USAGE)
        return
    }

    // Dispatched before the config is validated, because a `pair` invocation has no
    // bind address of its own and must not be refused by a TLS rule about one.
    if (args.firstOrNull() == SUBCOMMAND_PAIR) {
        exitProcess(runPairCommand(args))
    }

    val config = try {
        BridgeConfig.fromArgs(args, System.getenv()).also { it.validate() }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(EXIT_BAD_CONFIG)
    }

    val scope = CoroutineScope(SupervisorJob())
    val supervisor = CliSupervisor(config, scope)
    val pairing = PairingService(config.stateDirectory)
    val control = ControlSocket(config, pairing, scope)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("shutting down")
            control.close()
            supervisor.stop()
            scope.cancel()
        },
    )

    supervisor.start()

    try {
        control.start()
    } catch (e: IllegalStateException) {
        // Another bridge owns this state directory. Fatal on purpose -- see ControlSocket.
        System.err.println(e.message)
        exitProcess(EXIT_BAD_CONFIG)
    } catch (e: IOException) {
        // Only `bridge pair` is lost; the bridge itself is fine. A path over the
        // ~108-byte limit a Unix socket allows is the realistic cause.
        log.warn("no control socket, so `bridge pair` is unavailable: {}", e.message)
    }

    config.publicUrl?.let {
        // Unverifiable by design: at startup the tunnel in front of the bridge may
        // not be up yet, and self-connecting to check would make the bridge's boot
        // depend on Cloudflare's.
        log.info("advertising {} -- the bridge cannot verify that this address reaches it", config.advertisedUrl())
    }

    val color = TerminalQr.colorEnabled(System.getenv("NO_COLOR"))

    // Print a pairing code on every start when nothing has ever paired. A bridge
    // that starts silently with no paired device is a bridge the user cannot
    // reach and has no obvious way to fix.
    if (pairing.listDevices().isEmpty()) {
        println(pairingBanner(config, pairing.issueCode(PairingService.CodeSource.TERMINAL), color))
    } else {
        // No fabricated fallback here. advertisedUrl() returns null only for a
        // wildcard bind, and reconstructing "wss://0.0.0.0:8765/acp" would print
        // exactly the authoritative-looking address it refuses to produce -- on the
        // one branch that skips the banner, so the advisory explaining the fix
        // never appears either.
        val where = config.advertisedUrl()?.let { "on $it" } ?: "but has no address to advertise"
        println(
            "Bridge ready $where (${pairing.listDevices().size} paired device(s)). " +
                "Run `kiro-bridge pair` to add another -- no restart needed.",
        )
        pairingAdvisory(config)?.lineSequence()?.forEach { println("  $it") }
    }

    if (args.contains("--pair") && pairing.listDevices().isNotEmpty()) {
        println(pairingBanner(config, pairing.issueCode(PairingService.CodeSource.TERMINAL), color))
    }

    BridgeServer(config, supervisor, pairing, scope).start(wait = true)
}

/**
 * Mints a pairing code from a bridge that is already running.
 *
 * The code has to be minted *inside* the process that will redeem it, so this
 * cannot generate one on its own -- it asks over the control socket and prints
 * what comes back.
 *
 * The failure messages carry more weight than usual, because the overwhelmingly
 * likely mistake is running this as the wrong user: the systemd unit runs the
 * bridge as `bridge` with its own state directory, while an operator SSHes in as
 * themselves. "No bridge is running" while one plainly is would be a lie, so every
 * message names the exact path it looked at.
 */
private fun runPairCommand(args: Array<String>): Int {
    val config = BridgeConfig.fromArgs(args, System.getenv())
    val path = ControlSocket.pathFor(config.stateDirectory.toPath())

    val response = try {
        ControlSocket.request(path, ControlSocket.OP_PAIR)
    } catch (e: AccessDeniedException) {
        System.err.println(accessDeniedMessage(path, e))
        return EXIT_PERMISSION
    } catch (e: IOException) {
        System.err.println(noBridgeMessage(config, path, e))
        return EXIT_NO_BRIDGE
    } catch (e: IllegalStateException) {
        System.err.println(noBridgeMessage(config, path, e))
        return EXIT_NO_BRIDGE
    }

    val code = response.code
    if (!response.ok || code == null) {
        System.err.println(response.error ?: "The bridge refused to mint a pairing code.")
        return EXIT_REFUSED
    }

    println(
        pairingBanner(
            url = response.url,
            code = code,
            ttlSeconds = response.ttlSeconds ?: PairingService.CODE_TTL_SECONDS,
            advisory = response.advisory,
            // An operator who runs this twice has started their first QR dying. Say
            // so, and say it accurately -- a superseded code keeps working for a
            // grace rather than dropping dead, and promising the stronger thing
            // would send someone hunting a bug that is not there.
            footer = "Any code printed earlier stops working within " +
                "${PairingService.SUPERSEDED_GRACE_SECONDS} seconds.",
            printQr = config.printQr,
            color = TerminalQr.colorEnabled(System.getenv("NO_COLOR")),
        ),
    )
    return EXIT_OK
}

private fun noBridgeMessage(config: BridgeConfig, path: java.nio.file.Path, cause: Throwable): String {
    val stale = if (java.nio.file.Files.exists(path)) {
        "\n(A control socket is present but nothing answered on it -- a previous run " +
            "left it behind, and the next start will clean it up.)"
    } else {
        ""
    }
    return """
        No bridge is running with state directory ${config.stateDirectory}.

        `kiro-bridge pair` mints a code inside the bridge that will redeem it, so a
        bridge has to be running for it to have anywhere to go.$stale

        If the bridge runs as its own user -- which it does under the systemd unit --
        the state directory is that user's, not yours:

          sudo runuser -u bridge -- kiro-bridge pair --state-dir /home/bridge/.kiro-bridge

        Otherwise start one:  kiro-bridge --state-dir ${config.stateDirectory}
        (${cause.message})
    """.trimIndent()
}

private fun accessDeniedMessage(path: java.nio.file.Path, cause: Throwable): String = """
    The control socket at $path belongs to another user.

    The bridge only accepts control connections from the account it runs as, which
    under the systemd unit is `bridge` rather than you:

      sudo runuser -u bridge -- kiro-bridge pair --state-dir /home/bridge/.kiro-bridge
    (${cause.message})
""".trimIndent()

private const val SUBCOMMAND_PAIR = "pair"
private const val EXIT_OK = 0
private const val EXIT_REFUSED = 1
private const val EXIT_BAD_CONFIG = 2
private const val EXIT_NO_BRIDGE = 3
private const val EXIT_PERMISSION = 4

private val USAGE = """
    kiro-bridge — relays a phone to a Kiro cloud session through kiro-cli.

    It supervises `kiro-cli acp --agent-engine v3 --auth-method cli` and exposes it
    over an authenticated WebSocket. It needs no checkout, no git credentials and
    no working directory of consequence: for a cloud session, repositories are
    cloned inside Kiro's sandbox, not here.

    Usage: kiro-bridge [options]
           kiro-bridge pair [--state-dir <dir>]

    `kiro-bridge pair` prints a fresh pairing code, and a QR of it, from a bridge
    that is already running — no restart, and no dropped clients. It talks to that
    bridge over a socket in its state directory, so it must run on the same host,
    as the same user.

      --bind <address>     Default 127.0.0.1. Any other value requires TLS.
      --port <port>        Default ${BridgeConfig.DEFAULT_PORT}.
      --public-url <url>   What to tell the phone, when that is not what the bridge
                           binds to. Required behind a tunnel: the bridge binds
                           127.0.0.1 and cannot discover wss://your-host/acp from
                           that, so the QR would otherwise send the phone to
                           itself. Or set KIRO_BRIDGE_PUBLIC_URL.
      --no-qr              Print the pairing banner without the QR code. The
                           address and code are printed either way. Or set
                           KIRO_BRIDGE_NO_QR. NO_COLOR is honoured too, at the cost
                           of a QR that only scans on a dark terminal.
      --access-team-domain <domain>
                           Your Cloudflare Zero Trust team domain, e.g.
                           acme.cloudflareaccess.com. With --access-aud this turns
                           on GET /qr: a pairing page showing a QR that rotates
                           every ${QrPageBudget.ROTATE_SECONDS}s, served only to a browser Cloudflare Access
                           has signed in — so a phone can be paired with no SSH at
                           all. Or set KIRO_BRIDGE_ACCESS_TEAM_DOMAIN.
      --access-aud <tag>   The Access application's Application Audience (AUD) tag.
                           Or set KIRO_BRIDGE_ACCESS_AUD. Scope that application to
                           the /qr path only — the phone cannot complete a browser
                           sign-in, so gating /pair and /acp breaks pairing and the
                           session socket.
      --api-key <key>      Or set KIRO_API_KEY. Provisions this host without an
                           interactive login. Note it overrides any signed-in CLI
                           account, with no way to suppress it short of unsetting it.
      --tls-cert <file>    Required for a non-loopback bind.
      --tls-key <file>
      --kiro-cli <path>    Default: kiro-cli on PATH.
      --state-dir <dir>    Default ~/.kiro-bridge. Holds the paired-device list and
                           the control socket.
      --pair               Print a new pairing code at startup even if devices
                           already exist. Prefer `kiro-bridge pair`, which needs no
                           restart.

    Requirements on this host: the kiro-cli binary, a Kiro account signed in with a
    Pro plan or higher (or an API key), and outbound HTTPS.
""".trimIndent()
