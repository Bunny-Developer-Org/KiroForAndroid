package dev.kiro.bridge

import java.io.File

/**
 * How the bridge was told to run.
 *
 * Two of these fields are security decisions rather than preferences, and both
 * default closed: [bindAddress] is loopback unless someone explicitly opts out,
 * and [tlsCertificate] is *required* the moment the bind address is not loopback
 * (AUTHENTICATION §4).
 */
public data class BridgeConfig(
    val bindAddress: String = LOOPBACK,
    val port: Int = DEFAULT_PORT,
    val kiroCliPath: String = "kiro-cli",
    /**
     * Provisioned with an API key rather than an interactive login.
     *
     * A18 verified that `kiro-cli acp --agent-engine v3` authenticates from
     * `KIRO_API_KEY` and that the mode reaches cloud sessions. This is the easy
     * provisioning path: paste one key, no TTY, no provider-picker TUI.
     *
     * It is not a substitute for F-08. The key authenticates *this host*, not the
     * person holding the phone.
     */
    val apiKey: String? = null,
    val tlsCertificate: File? = null,
    val tlsPrivateKey: File? = null,
    val stateDirectory: File = File(System.getProperty("user.home"), ".kiro-bridge"),
    /**
     * Pinned deliberately. Cloud sessions ignore it, but a stray *local* session
     * created here would otherwise appear in the app's list (ADR-005 §2).
     */
    val workingDirectory: File = File(System.getProperty("java.io.tmpdir"), "kiro-bridge-workspace"),
    val replayBufferSize: Int = DEFAULT_REPLAY_BUFFER,
) {

    val isLoopbackOnly: Boolean get() = bindAddress == LOOPBACK || bindAddress == "localhost"

    val tlsEnabled: Boolean get() = tlsCertificate != null && tlsPrivateKey != null

    /**
     * Refuses to start in a configuration that would put a token-bearing
     * WebSocket on a LAN in the clear.
     */
    public fun validate() {
        require(port in 1..PORT_MAX) { "port $port is out of range" }
        if (!isLoopbackOnly && !tlsEnabled) {
            throw IllegalArgumentException(
                """
                Refusing to bind $bindAddress without TLS.

                A non-loopback bind puts the pairing handshake and every device
                token on the network in the clear. Pass --tls-cert and --tls-key,
                or leave the bridge on $LOOPBACK and reach it through a tunnel.
                """.trimIndent(),
            )
        }
    }

    /**
     * The environment handed to the `kiro-cli` child.
     *
     * Built explicitly rather than inherited, and that is a finding rather than a
     * style choice: A18 established that `KIRO_API_KEY` overrides the CLI
     * credential store *even when `--auth-method cli` is passed*, with no flag to
     * suppress it. Inheriting the host environment would let a stray variable
     * silently change which Kiro account every session runs as, with nothing in
     * the app reflecting the switch.
     */
    public fun childEnvironment(): Map<String, String> = buildMap {
        // Enough for the CLI to find its own runtime and its credential store.
        listOf("HOME", "PATH", "LANG", "LC_ALL", "TMPDIR", "XDG_DATA_HOME", "XDG_CONFIG_HOME")
            .forEach { key -> System.getenv(key)?.let { put(key, it) } }
        apiKey?.let { put("KIRO_API_KEY", it) }
    }

    public companion object {
        public const val LOOPBACK: String = "127.0.0.1"
        public const val DEFAULT_PORT: Int = 8765
        public const val PORT_MAX: Int = 65535

        /**
         * F-01 measured 991 updates replayed on one real cloud session, so a buffer
         * sized for a "typical" turn is sized wrong.
         */
        public const val DEFAULT_REPLAY_BUFFER: Int = 4096

        public fun fromArgs(args: Array<String>, env: Map<String, String>): BridgeConfig {
            val flags = args.toFlagMap()
            val bind = flags["bind"] ?: env["KIRO_BRIDGE_BIND"] ?: LOOPBACK
            return BridgeConfig(
                bindAddress = bind,
                port = (flags["port"] ?: env["KIRO_BRIDGE_PORT"])?.toIntOrNull() ?: DEFAULT_PORT,
                kiroCliPath = flags["kiro-cli"] ?: env["KIRO_CLI_PATH"] ?: "kiro-cli",
                apiKey = flags["api-key"] ?: env["KIRO_API_KEY"],
                tlsCertificate = flags["tls-cert"]?.let(::File),
                tlsPrivateKey = flags["tls-key"]?.let(::File),
                stateDirectory = flags["state-dir"]?.let(::File)
                    ?: File(System.getProperty("user.home"), ".kiro-bridge"),
            )
        }

        private fun Array<String>.toFlagMap(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            var index = 0
            while (index < size) {
                val token = this[index]
                if (token.startsWith("--")) {
                    val name = token.removePrefix("--")
                    val inline = name.substringAfter('=', missingDelimiterValue = "")
                    if (inline.isNotEmpty()) {
                        map[name.substringBefore('=')] = inline
                    } else if (index + 1 < size && !this[index + 1].startsWith("--")) {
                        map[name] = this[index + 1]
                        index++
                    } else {
                        map[name] = "true"
                    }
                }
                index++
            }
            return map
        }
    }
}
