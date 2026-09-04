package dev.kiro.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeConfigTest {

    @Test
    fun `defaults to loopback`() {
        val config = BridgeConfig.fromArgs(emptyArray(), emptyMap())
        assertTrue(config.isLoopbackOnly)
        config.validate()
    }

    /**
     * A non-loopback bind without TLS puts the pairing handshake and every device
     * token on the LAN in the clear. Refusing to start is the correct behaviour,
     * not a warning.
     */
    @Test
    fun `refuses a non-loopback bind without TLS`() {
        val config = BridgeConfig(bindAddress = "0.0.0.0")
        val failure = assertFailsWith<IllegalArgumentException> { config.validate() }
        assertTrue(failure.message!!.contains("TLS"))
    }

    /**
     * A18's security consequence, tested. `KIRO_API_KEY` overrides the CLI
     * credential store even when `--auth-method cli` is passed, and there is no
     * flag to suppress it — so a bridge that inherited its environment could
     * silently run every session as a different Kiro account.
     */
    @Test
    fun `the child environment is built explicitly and not inherited`() {
        val withoutKey = BridgeConfig().childEnvironment()
        assertFalse(
            withoutKey.containsKey("KIRO_API_KEY"),
            "a stray host variable must not decide which account sessions run as",
        )

        val withKey = BridgeConfig(apiKey = "abc123").childEnvironment()
        assertEquals("abc123", withKey["KIRO_API_KEY"])

        // Only the variables the CLI genuinely needs are forwarded.
        assertTrue(withKey.keys.all { it in ALLOWED })
    }

    @Test
    fun `parses flags in both spellings`() {
        val config = BridgeConfig.fromArgs(
            arrayOf("--port=9000", "--bind", "0.0.0.0", "--api-key", "k"),
            emptyMap(),
        )
        assertEquals(9000, config.port)
        assertEquals("0.0.0.0", config.bindAddress)
        assertEquals("k", config.apiKey)
    }

    @Test
    fun `an explicit flag beats the environment`() {
        val config = BridgeConfig.fromArgs(
            arrayOf("--api-key", "from-flag"),
            mapOf("KIRO_API_KEY" to "from-env"),
        )
        assertEquals("from-flag", config.apiKey)
    }

    /**
     * The whole reason `--public-url` exists: behind a tunnel the bridge binds
     * loopback and the phone reaches a public hostname, and only an operator can
     * connect the two.
     */
    @Test
    fun `--public-url is what gets advertised, not the bind address`() {
        val config = BridgeConfig.fromArgs(
            arrayOf("--public-url", "wss://bridge.example.com/acp"),
            emptyMap(),
        )

        assertEquals("wss://bridge.example.com/acp", config.advertisedUrl())
        config.validate()
    }

    /**
     * `https://host` is what someone copies out of a browser or a Cloudflare
     * dashboard. It names the same endpoint, so rejecting it over a scheme the
     * operator never chose would be pedantry.
     */
    @Test
    fun `a public URL is normalised to a wss address ending in acp`() {
        fun advertised(url: String) =
            BridgeConfig.fromArgs(arrayOf("--public-url", url), emptyMap()).advertisedUrl()

        assertEquals("wss://h.example.com/acp", advertised("https://h.example.com"))
        assertEquals("wss://h.example.com/acp", advertised("h.example.com"))
        assertEquals("wss://h.example.com/acp", advertised("wss://h.example.com/"))
        assertEquals("wss://h.example.com/acp", advertised("wss://h.example.com/acp"))
        assertEquals("wss://h.example.com:8765/acp", advertised("h.example.com:8765"))
    }

    /** Set through the environment is how a systemd unit will actually carry it. */
    @Test
    fun `the public URL can come from the environment`() {
        val config = BridgeConfig.fromArgs(
            emptyArray(),
            mapOf("KIRO_BRIDGE_PUBLIC_URL" to "wss://h.example.com/acp"),
        )

        assertEquals("wss://h.example.com/acp", config.advertisedUrl())
    }

    /**
     * The same objection `--bind` already raises, arriving from the other end: a
     * plaintext address in a QR tells a phone to send its pairing code, and later
     * its device token, in the clear.
     */
    @Test
    fun `a plaintext public URL to a non-loopback host is refused`() {
        val config = BridgeConfig.fromArgs(arrayOf("--public-url", "ws://bridge.example.com/acp"), emptyMap())

        val failure = assertFailsWith<IllegalArgumentException> { config.validate() }
        assertTrue(failure.message!!.contains("TLS"), "the message should name the missing thing")
    }

    /** `adb reverse` is plaintext to loopback, and it is the supported device path. */
    @Test
    fun `a plaintext public URL to loopback is allowed`() {
        BridgeConfig.fromArgs(arrayOf("--public-url", "ws://127.0.0.1:8765/acp"), emptyMap()).validate()
        BridgeConfig.fromArgs(arrayOf("--public-url", "ws://localhost:8765/acp"), emptyMap()).validate()
    }

    /**
     * `wss://0.0.0.0:8765/acp` in a QR is worse than no QR at all: it looks
     * authoritative and can never work. Better to print nothing and say why.
     */
    @Test
    fun `a wildcard bind with no public URL advertises nothing`() {
        val config = BridgeConfig(bindAddress = "0.0.0.0", publicUrl = null)

        assertEquals(null, config.advertisedUrl())
        assertTrue(config.isWildcardBind)
    }

    /** Unset and loopback is not a fallback -- it is the right answer for `adb reverse`. */
    @Test
    fun `an unset public URL on a loopback bridge advertises loopback`() {
        assertEquals("ws://127.0.0.1:8765/acp", BridgeConfig().advertisedUrl())
    }

    @Test
    fun `the QR can be suppressed by flag or environment`() {
        assertTrue(BridgeConfig.fromArgs(emptyArray(), emptyMap()).printQr)
        assertFalse(BridgeConfig.fromArgs(arrayOf("--no-qr"), emptyMap()).printQr)
        assertFalse(BridgeConfig.fromArgs(emptyArray(), mapOf("KIRO_BRIDGE_NO_QR" to "1")).printQr)
    }

    /**
     * `--no-qr` is valueless, so the arg parser would otherwise swallow the
     * following token as its value — and the only bare token the bridge takes is
     * the `pair` subcommand.
     */
    @Test
    fun `--no-qr does not consume a following subcommand`() {
        assertFalse(BridgeConfig.fromArgs(arrayOf("--no-qr", "pair"), emptyMap()).printQr)
    }

    /**
     * `startsWith("127.")` was not good enough, and the gap was a credential leak:
     * `127.internal.example` begins with those four characters, resolves wherever
     * DNS says, and would have licensed a plaintext address carrying a pairing code
     * and then a device token across the network in the clear.
     */
    @Test
    fun `a hostname that merely starts with 127 is not loopback`() {
        listOf(
            "ws://127.internal.example:8765/acp",
            "ws://127.0.0.1.evil.example/acp",
            "ws://127a.example/acp",
        ).forEach { url ->
            val config = BridgeConfig.fromArgs(arrayOf("--public-url", url), emptyMap())
            val failure = assertFailsWith<IllegalArgumentException>("expected $url to be refused") { config.validate() }
            assertTrue(failure.message!!.contains("TLS"), failure.message!!)
        }

        // Real loopback quads still pass, including one that is not 127.0.0.1.
        BridgeConfig.fromArgs(arrayOf("--public-url", "ws://127.0.0.53:8765/acp"), emptyMap()).validate()
    }

    /**
     * `normalisePublicUrl` matches on a lowercased copy but returns the original, so
     * an uppercase scheme survives it. A case-sensitive TLS check would then wave
     * plaintext straight through.
     */
    @Test
    fun `the TLS check is not fooled by an uppercase scheme`() {
        val config = BridgeConfig.fromArgs(arrayOf("--public-url", "WS://192.168.1.20:8765/acp"), emptyMap())

        val failure = assertFailsWith<IllegalArgumentException> { config.validate() }
        assertTrue(failure.message!!.contains("TLS"), failure.message!!)
    }

    /**
     * Refused at startup, where it is the operator's own typo, rather than minutes
     * later on someone else's phone as "that pairing code could not be read" —
     * which blames the code rather than the flag.
     */
    @Test
    fun `an unrecognised scheme is refused rather than advertised`() {
        listOf("tcp://h.example.com", "wws://h.example.com", "file:///etc/passwd").forEach { url ->
            val config = BridgeConfig.fromArgs(arrayOf("--public-url", url), emptyMap())
            assertFailsWith<IllegalArgumentException>("expected $url to be refused") { config.validate() }
        }
    }

    /**
     * `--public-url "$UNSET_VAR"` from a wrapper script is the ordinary way to get a
     * blank one, and a valueless `--public-url` becomes the string "true" in the
     * flag map — which would otherwise be advertised as `wss://true/acp`.
     */
    @Test
    fun `a blank or valueless public URL is treated as unset`() {
        assertEquals(null, BridgeConfig.fromArgs(arrayOf("--public-url", ""), emptyMap()).publicUrl)
        assertEquals(null, BridgeConfig.fromArgs(arrayOf("--public-url"), emptyMap()).publicUrl)
        assertEquals(null, BridgeConfig.fromArgs(emptyArray(), mapOf("KIRO_BRIDGE_PUBLIC_URL" to "")).publicUrl)

        // Falls back to the loopback bind rather than to wss:///acp or wss://true/acp.
        assertEquals(
            "ws://127.0.0.1:8765/acp",
            BridgeConfig.fromArgs(arrayOf("--public-url"), emptyMap()).advertisedUrl(),
        )
    }

    @Test
    fun `the access flags are read from a flag or the environment, flag first`() {
        val fromFlags = BridgeConfig.fromArgs(
            arrayOf("--access-team-domain", "acme.cloudflareaccess.com", "--access-aud", "abc123"),
            mapOf("KIRO_BRIDGE_ACCESS_AUD" to "from-env"),
        )
        assertEquals("acme.cloudflareaccess.com", fromFlags.accessTeamDomain)
        assertEquals("abc123", fromFlags.accessAudience)
        assertTrue(fromFlags.accessEnabled)

        val fromEnv = BridgeConfig.fromArgs(
            emptyArray(),
            mapOf(
                "KIRO_BRIDGE_ACCESS_TEAM_DOMAIN" to "acme.cloudflareaccess.com",
                "KIRO_BRIDGE_ACCESS_AUD" to "abc123",
            ),
        )
        assertTrue(fromEnv.accessEnabled)
    }

    /**
     * `--access-aud --port 9000` would otherwise set the audience to the literal
     * "true". No JWT carries that, so /qr would 403 forever with a message about
     * Access rather than about a mistyped flag — closed, and unexplainable.
     */
    @Test
    fun `a valueless access flag does not become the literal string true`() {
        assertEquals(null, BridgeConfig.fromArgs(arrayOf("--access-aud"), emptyMap()).accessAudience)
        assertEquals(null, BridgeConfig.fromArgs(arrayOf("--access-team-domain"), emptyMap()).accessTeamDomain)
    }

    /** Half-configured Access looks configured to its operator and then 403s forever. */
    @Test
    fun `half-configured Access refuses to start`() {
        listOf(
            arrayOf("--access-team-domain", "acme.cloudflareaccess.com"),
            arrayOf("--access-aud", "abc123"),
        ).forEach { args ->
            val failure = assertFailsWith<IllegalArgumentException> {
                BridgeConfig.fromArgs(args, emptyMap()).validate()
            }
            assertTrue("half-configured" in failure.message!!, failure.message!!)
        }
    }

    /** Operators paste the dashboard URL; the JWKS URL and the `iss` check need a bare host. */
    @Test
    fun `a team domain pasted as a URL is normalised`() {
        val config = BridgeConfig.fromArgs(
            arrayOf("--access-team-domain", "https://acme.cloudflareaccess.com/", "--access-aud", "abc"),
            emptyMap(),
        )

        assertEquals("acme.cloudflareaccess.com", config.accessTeamDomain)
        config.validate()
    }

    /**
     * The nastiest of these is a trust bypass, not a typo:
     * `acme.cloudflareaccess.com@attacker.example` is a legal URI whose *host* is
     * `attacker.example`, so the bridge would fetch signing keys from a host the
     * attacker controls — and the `iss` check would pass too, since it compares the
     * same string. An attacker-published key set would verify end to end.
     */
    @Test
    fun `a team domain that is not a bare hostname is refused`() {
        listOf(
            "acme.cloudflareaccess.com@attacker.example",
            "acme.cloudflareaccess.com:8443",
            "acme.cloudflareaccess.com/cdn-cgi",
            "acme.cloudflareaccess.com?x=1",
            "acme.cloudflareaccess.com#x",
            "acme..cloudflareaccess.com",
            "-acme.cloudflareaccess.com",
            "nodots",
        ).forEach { domain ->
            assertFailsWith<IllegalArgumentException>("expected $domain to be refused") {
                BridgeConfig.fromArgs(arrayOf("--access-team-domain", domain, "--access-aud", "abc"), emptyMap())
                    .validate()
            }
        }
    }

    /** `https://` alone strips to nothing, which must read as unset rather than configured. */
    @Test
    fun `a team domain that normalises to nothing is treated as unset`() {
        val config = BridgeConfig.fromArgs(arrayOf("--access-team-domain", "https://"), emptyMap())

        assertEquals(null, config.accessTeamDomain)
        assertFalse(config.accessEnabled)
        config.validate()
    }

    /** /qr is opt-in. A loopback bridge behind `adb reverse` will never have Access. */
    @Test
    fun `Access unconfigured is still a valid configuration`() {
        val config = BridgeConfig.fromArgs(emptyArray(), emptyMap())

        config.validate()
        assertFalse(config.accessEnabled)
    }

    private companion object {
        val ALLOWED = setOf(
            "HOME",
            "PATH",
            "LANG",
            "LC_ALL",
            "TMPDIR",
            "XDG_DATA_HOME",
            "XDG_CONFIG_HOME",
            "KIRO_API_KEY",
        )
    }
}
