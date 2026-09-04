package dev.kiro.bridge

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    /**
     * What to tell a phone, when that is not what the bridge binds to.
     *
     * Behind a tunnel the bridge binds `127.0.0.1:8765` while the phone reaches
     * `wss://your-host/acp`, and there is no way to discover the second from the
     * first -- `cloudflared` connects inbound like any other local client and
     * leaves no trace of the public name. So an operator has to say (HOSTING §5).
     *
     * Leaving it unset is correct for exactly one shape: a bridge the phone
     * reaches over loopback, which is `tools/run-on-device.sh`'s `adb reverse`.
     */
    val publicUrl: String? = null,
    /** Suppress the QR in the pairing banner. The address and code are printed either way. */
    val printQr: Boolean = true,
    /**
     * The Cloudflare Zero Trust team domain, e.g. `acme.cloudflareaccess.com`.
     *
     * Set together with [accessAudience], this turns on `GET /qr` (F-29) and nothing
     * else. Not a secret -- it appears in every Access URL -- so a flag is fine.
     */
    val accessTeamDomain: String? = null,
    /** The Access application's Application Audience (AUD) tag. Also not a secret. */
    val accessAudience: String? = null,
    val tlsCertificate: File? = null,
    val tlsPrivateKey: File? = null,
    val stateDirectory: File = File(System.getProperty("user.home"), ".kiro-bridge"),
    /**
     * Pinned deliberately. Cloud sessions ignore it, but a stray *local* session
     * created here would otherwise appear in the app's list (ADR-005 §2).
     */
    val workingDirectory: File = File(System.getProperty("java.io.tmpdir"), "kiro-bridge-workspace"),
    val replayBufferSize: Int = DEFAULT_REPLAY_BUFFER,
    /**
     * How often `/acp` sends a WebSocket ping.
     *
     * A client that goes silently dead -- a dropped network, or (seen in the
     * field) a stale `adb forward` -- never sends a close frame and, unlike a
     * crash or a clean shutdown, never triggers a TCP FIN or RST either. With
     * no heartbeat, that connection just sits in the roster forever: nothing
     * ever throws on `incoming`, so `clients` only grows. This, paired with
     * [webSocketPongTimeout], is what actually reaps it.
     */
    val webSocketPingPeriod: Duration = 20.seconds,
    /** How long `/acp` waits for a pong before giving up on a ping and closing. */
    val webSocketPongTimeout: Duration = 15.seconds,
) {

    val isLoopbackOnly: Boolean get() = bindAddress == LOOPBACK || bindAddress == "localhost"

    val tlsEnabled: Boolean get() = tlsCertificate != null && tlsPrivateKey != null

    /** A bind that means "every interface", and therefore names no address a phone can use. */
    val isWildcardBind: Boolean get() = bindAddress in WILDCARD_BINDS

    /** Whether `GET /qr` is served at all. Opt-in: a loopback bridge will never have it. */
    val accessEnabled: Boolean get() = accessTeamDomain != null && accessAudience != null

    public fun scheme(): String = if (tlsEnabled) "wss" else "ws"

    /**
     * The address to print, put in a QR, and hand to a phone.
     *
     * @return null when the bridge genuinely cannot know it -- a wildcard bind with
     *   no [publicUrl]. Printing `wss://0.0.0.0:8765/acp` in that case would be
     *   worse than printing nothing: it looks authoritative and cannot ever work.
     */
    public fun advertisedUrl(): String? = when {
        publicUrl != null -> normalisePublicUrl(publicUrl)
        isWildcardBind -> null
        // Loopback here is not a fallback but the correct answer for `adb reverse`,
        // where the phone really does reach the bridge at 127.0.0.1.
        else -> "${scheme()}://$bindAddress:$port/acp"
    }

    /**
     * Refuses to start in a configuration that would put a token-bearing
     * WebSocket on a LAN in the clear.
     */
    public fun validate() {
        require(port in 1..PORT_MAX) { "port $port is out of range" }

        validateAccess()
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

        val advertised = advertisedUrl() ?: return
        // Lowercased before every comparison below. `normalisePublicUrl` only
        // lowercases what it *matches* on, so `WS://host` survives it intact -- and
        // a case-sensitive check here would wave exactly that through, plaintext.
        val scheme = advertised.substringBefore("://", missingDelimiterValue = "").lowercase()

        // A scheme this does not recognise cannot be reasoned about, and letting it
        // start means the operator's typo surfaces minutes later on someone else's
        // phone as "that pairing code could not be read" -- blaming the code rather
        // than the flag.
        if (scheme != "ws" && scheme != "wss") {
            throw IllegalArgumentException(
                "Refusing to advertise $advertised: --public-url must be a ws:// or wss:// address " +
                    "(https:// and a bare hostname are accepted and rewritten).",
            )
        }

        // The same objection as the bind check above, arriving from the other end:
        // a plaintext address in a QR sends a phone to hand over its pairing code,
        // and later its device token, in the clear. ws:// to loopback stays legal
        // because that is `adb reverse`, where the traffic never leaves the phone.
        if (scheme == "ws" && !isLoopbackHost(hostOf(advertised))) {
            throw IllegalArgumentException(
                """
                Refusing to advertise $advertised without TLS.

                A phone told to use a plaintext address sends its pairing code, and
                then every device token, over the network in the clear. Advertise a
                wss:// address -- a tunnel terminates TLS for you (HOSTING §5).
                """.trimIndent(),
            )
        }
    }

    /**
     * Extracted from [validate] only to keep detekt's throw budget honest; every rule
     * here is as load-bearing as the ones it left behind.
     */
    private fun validateAccess() {
        // Half-configured Access looks configured to its operator and then 403s every
        // request forever. Refusing at startup beats discovering that from a browser
        // with no idea which half is missing.
        require((accessTeamDomain == null) == (accessAudience == null)) {
            "Access is half-configured: --access-team-domain and --access-aud are both required, " +
                "or neither. Without both, GET /qr stays off (HOSTING §5)."
        }
        // A hostname pattern, not a blocklist, and this is a trust boundary rather
        // than tidiness. `acme.cloudflareaccess.com@attacker.example` is a legal URI
        // whose *host* is `attacker.example` -- the team name becomes userinfo -- so
        // the bridge would fetch signing keys from a host the attacker controls, and
        // the `iss` check would pass too, because it compares the same string. An
        // attacker-published key set would then verify end to end.
        accessTeamDomain?.let { domain ->
            require(TEAM_DOMAIN_PATTERN.matches(domain)) {
                "--access-team-domain must be a bare hostname like acme.cloudflareaccess.com, not $domain"
            }
        }
        accessAudience?.let { aud ->
            require(aud.none(Char::isWhitespace)) { "--access-aud must not contain whitespace" }
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

        private val WILDCARD_BINDS = setOf("0.0.0.0", "::", "[::]", "*")

        /**
         * Dotted DNS labels and nothing else -- no userinfo, port, path, query or
         * fragment. See [validateAccess] for why an `@` here is a trust bypass.
         */
        private val TEAM_DOMAIN_PATTERN =
            Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$")

        /**
         * Accepts what an operator might reasonably write, and settles on the one
         * form the app understands: a `ws`/`wss` URL ending in the `/acp` path.
         *
         * `https://host` is the spelling people will copy out of a browser or a
         * Cloudflare dashboard, and rejecting it over a scheme they never chose
         * would be pedantry -- it names the same endpoint.
         */
        private fun normalisePublicUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            val lower = trimmed.lowercase()
            val withScheme = when {
                lower.startsWith("ws://") || lower.startsWith("wss://") -> trimmed
                lower.startsWith("https://") -> "wss://" + trimmed.substringAfter("://")
                lower.startsWith("http://") -> "ws://" + trimmed.substringAfter("://")
                // Leave an unrecognised scheme alone; validate() is where it dies,
                // with a message about TLS rather than a silently rewritten URL.
                "://" in trimmed -> trimmed
                else -> "wss://$trimmed"
            }
            val afterScheme = withScheme.substringAfter("://")
            return if ('/' in afterScheme) withScheme else "$withScheme/acp"
        }

        /**
         * Operators paste the dashboard URL, so accept it.
         *
         * Must run *before* the value reaches the JWKS URL and the `iss` comparison,
         * or both are silently wrong and every token is rejected with no clue why.
         */
        private fun normaliseTeamDomain(raw: String): String =
            raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

        /** Splits a trailing `:port` off, leaving an IPv6 literal's own colons alone. */
        private fun hostOf(url: String): String {
            val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
            return if (authority.startsWith("[")) {
                authority.substringBefore(']').removePrefix("[")
            } else {
                authority.substringBefore(':')
            }
        }

        /**
         * A prefix match on `"127."` is not good enough here, and the difference is
         * a credential leak: the hostname `127.internal.example` starts with those
         * four characters, resolves wherever DNS says, and would have licensed a
         * plaintext `ws://` address that carries a pairing code and then a device
         * token across the network in the clear. Only a real dotted quad counts.
         */
        private fun isLoopbackHost(host: String): Boolean {
            if (host == "localhost" || host == "::1") return true
            val octets = host.split('.')
            return octets.size == 4 &&
                octets.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toInt() in 0..MAX_OCTET } &&
                octets[0] == "127"
        }

        private const val MAX_OCTET = 255

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
                // `takeUnless { it == "true" }` is not paranoia: toFlagMap gives a
                // flag with no value the string "true", so `--public-url` followed
                // by another flag would otherwise advertise `wss://true/acp`. And
                // the blank guard has to cover the *flag* too -- a wrapper script
                // running `--public-url "$UNSET_VAR"` is the ordinary way to get
                // one, and it would otherwise advertise `wss:///acp`.
                publicUrl = flags["public-url"]?.takeUnless { it == "true" }?.ifBlank { null }
                    ?: env["KIRO_BRIDGE_PUBLIC_URL"]?.ifBlank { null },
                // Same valueless-flag guard as publicUrl, and it matters more here:
                // `--access-aud --port 9000` would otherwise set the audience to the
                // literal "true". No JWT carries that, so /qr would 403 forever with
                // a message about Access rather than about a mistyped flag.
                accessTeamDomain = (
                    flags["access-team-domain"]?.takeUnless { it == "true" }
                        ?: env["KIRO_BRIDGE_ACCESS_TEAM_DOMAIN"]
                    // Blank-checked *after* normalising: a bare "https://" strips to
                    // the empty string, which would otherwise read as configured.
                    )?.let(::normaliseTeamDomain)?.ifBlank { null },
                accessAudience = flags["access-aud"]?.takeUnless { it == "true" }?.ifBlank { null }
                    ?: env["KIRO_BRIDGE_ACCESS_AUD"]?.ifBlank { null },
                // Presence, not value. toFlagMap gives a valueless flag the string
                // "true", but `--no-qr pair` would swallow the subcommand as its
                // value instead -- and a boolean flag never has a meaningful one.
                printQr = !flags.containsKey("no-qr") && env["KIRO_BRIDGE_NO_QR"].isNullOrBlank(),
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
