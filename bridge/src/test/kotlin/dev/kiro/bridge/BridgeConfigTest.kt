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

    private companion object {
        val ALLOWED = setOf(
            "HOME", "PATH", "LANG", "LC_ALL", "TMPDIR",
            "XDG_DATA_HOME", "XDG_CONFIG_HOME", "KIRO_API_KEY",
        )
    }
}
