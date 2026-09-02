package dev.kiro.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("dev.kiro.bridge.Main")

public fun main(args: Array<String>) {
    if (args.contains("--help") || args.contains("-h")) {
        println(USAGE)
        return
    }

    val config = try {
        BridgeConfig.fromArgs(args, System.getenv()).also { it.validate() }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }

    val scope = CoroutineScope(SupervisorJob())
    val supervisor = CliSupervisor(config, scope)
    val pairing = PairingService(config.stateDirectory)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("shutting down")
            supervisor.stop()
            scope.cancel()
        },
    )

    supervisor.start()

    // Print a pairing code on every start when nothing has ever paired. A bridge
    // that starts silently with no paired device is a bridge the user cannot
    // reach and has no obvious way to fix.
    if (pairing.listDevices().isEmpty()) {
        val code = pairing.issueCode()
        println(pairingBanner(config, code))
    } else {
        println(
            "Bridge ready on ${config.scheme()}://${config.bindAddress}:${config.port}/acp " +
                "(${pairing.listDevices().size} paired device(s)). " +
                "Run with --pair to add another.",
        )
    }

    if (args.contains("--pair") && pairing.listDevices().isNotEmpty()) {
        println(pairingBanner(config, pairing.issueCode()))
    }

    BridgeServer(config, supervisor, pairing, scope).start(wait = true)
}

private fun BridgeConfig.scheme(): String = if (tlsEnabled) "wss" else "ws"

private fun pairingBanner(config: BridgeConfig, code: String): String = buildString {
    val url = "${config.scheme()}://${config.bindAddress}:${config.port}/acp"
    appendLine()
    appendLine("  Pair this bridge with your phone")
    appendLine("  ────────────────────────────────")
    appendLine("  Address : $url")
    appendLine("  Code    : $code   (valid for ${PairingService.CODE_TTL_SECONDS / 60} minutes, single use)")
    appendLine()
    if (config.isLoopbackOnly) {
        appendLine("  This bridge is bound to loopback, so only this machine can reach it.")
        appendLine("  To use it from a phone, put it behind a tunnel, or rebind with")
        appendLine("  --bind 0.0.0.0 --tls-cert <file> --tls-key <file>.")
    }
    if (config.apiKey != null) {
        appendLine("  Auth    : KIRO_API_KEY (this host's key, not a per-user sign-in).")
    }
}

private val USAGE = """
    kiro-bridge — relays a phone to a Kiro cloud session through kiro-cli.

    It supervises `kiro-cli acp --agent-engine v3 --auth-method cli` and exposes it
    over an authenticated WebSocket. It needs no checkout, no git credentials and
    no working directory of consequence: for a cloud session, repositories are
    cloned inside Kiro's sandbox, not here.

    Usage: kiro-bridge [options]

      --bind <address>     Default 127.0.0.1. Any other value requires TLS.
      --port <port>        Default ${BridgeConfig.DEFAULT_PORT}.
      --api-key <key>      Or set KIRO_API_KEY. Provisions this host without an
                           interactive login. Note it overrides any signed-in CLI
                           account, with no way to suppress it short of unsetting it.
      --tls-cert <file>    Required for a non-loopback bind.
      --tls-key <file>
      --kiro-cli <path>    Default: kiro-cli on PATH.
      --state-dir <dir>    Default ~/.kiro-bridge. Holds the paired-device list.
      --pair               Print a new pairing code even if devices already exist.

    Requirements on this host: the kiro-cli binary, a Kiro account signed in with a
    Pro plan or higher (or an API key), and outbound HTTPS.
""".trimIndent()
