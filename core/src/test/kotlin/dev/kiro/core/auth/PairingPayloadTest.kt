package dev.kiro.core.auth

import dev.kiro.core.auth.PairingPayload.DecodeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingPayloadTest {

    /**
     * The round trip, plus the one assertion that guards a silent, total failure:
     * `AcpJson` sets `encodeDefaults = false`, and reusing it here (or giving
     * `version` a default) would drop `"v"` from every QR the bridge prints. Every
     * app would then read every QR as NotAPairingCode, with a green build.
     */
    @Test
    fun `a payload round-trips, and the version survives encoding`() {
        val encoded = PairingPayload.encode("wss://bridge.example.com/acp", "8DD6YW6X")

        assertTrue("\"v\":1" in encoded, "the version field was dropped: $encoded")

        val decoded = assertIs<DecodeResult.Ok>(PairingPayload.decode(encoded))
        assertEquals("wss://bridge.example.com/acp", decoded.payload.url)
        assertEquals("8DD6YW6X", decoded.payload.code)
        assertEquals(PairingPayload.VERSION, decoded.payload.version)
    }

    /**
     * Tolerant parsing (ADR-003 §3) is how this format grows. A bridge that starts
     * sending a new field must not break every app already in the field.
     */
    @Test
    fun `an unknown field is ignored rather than fatal`() {
        val raw = """{"v":1,"url":"wss://h/acp","code":"ABCD2345","fingerprint":"sha256:x"}"""

        val decoded = assertIs<DecodeResult.Ok>(PairingPayload.decode(raw))
        assertEquals("ABCD2345", decoded.payload.code)
    }

    /**
     * A camera pointed at the world meets far more non-payloads than payloads, so
     * this is the *common* path, not an error path. It has to be told apart from a
     * broken Kiro code: "that isn't a Kiro code" and "that code is damaged" send a
     * user to two different places.
     */
    @Test
    fun `a QR that is not ours is reported as not ours, not as malformed`() {
        listOf(
            "https://example.com",
            "WIFI:S:home;T:WPA;P:hunter2;;",
            "BEGIN:VCARD\nVERSION:3.0\nEND:VCARD",
            "{}",
            """{"ssid":"home","psk":"hunter2"}""",
            "",
            "   ",
            "8DD6YW6X",
        ).forEach { raw ->
            assertEquals(DecodeResult.NotAPairingCode, PairingPayload.decode(raw), "for: $raw")
        }
    }

    /**
     * Past the version number, `url` and `code` stop being promises. Reading them
     * anyway would be guessing at a format we do not have.
     */
    @Test
    fun `a newer version is refused rather than half-understood`() {
        val raw = """{"v":2,"url":"wss://h/acp","code":"ABCD2345"}"""

        assertEquals(DecodeResult.UnsupportedVersion(2), PairingPayload.decode(raw))
    }

    /**
     * The version has to be settled *before* the strict deserializer runs, because a
     * version bump is exactly the case where `url` and `code` are gone — that is
     * what the number is for. Decoding first throws on the missing field and reports
     * a newer bridge's QR as "not a pairing code at all", which sends the user off
     * to rescan a code that will never work instead of telling them to update.
     */
    @Test
    fun `a newer version is recognised even when it renamed the fields`() {
        val raw = """{"v":2,"endpoint":"wss://h/acp","token":"ABCD2345"}"""

        assertEquals(DecodeResult.UnsupportedVersion(2), PairingPayload.decode(raw))
    }

    /** Same ordering requirement, from below: v0 is ours and broken, not foreign. */
    @Test
    fun `a version below one is malformed rather than unrecognised`() {
        assertIs<DecodeResult.Malformed>(PairingPayload.decode("""{"v":0,"url":"wss://h/acp","code":"ABCD2345"}"""))
        assertIs<DecodeResult.Malformed>(PairingPayload.decode("""{"v":-1,"url":"wss://h/acp","code":"ABCD2345"}"""))
    }

    /**
     * A quoted version is read as the number, which is tolerant parsing (ADR-003 §3)
     * rather than an oversight: `v` only selects which reading of the payload
     * applies, so being generous about its spelling costs nothing. A `v` that is not
     * a number in any sense is somebody else's JSON and gets the "not ours" message.
     */
    @Test
    fun `a quoted version is tolerated, a non-numeric one is not ours`() {
        assertIs<DecodeResult.Ok>(PairingPayload.decode("""{"v":"1","url":"wss://h/acp","code":"ABCD2345"}"""))
        assertEquals(
            DecodeResult.UnsupportedVersion(2),
            PairingPayload.decode("""{"v":"2","url":"wss://h/acp","code":"ABCD2345"}"""),
        )
        assertEquals(
            DecodeResult.NotAPairingCode,
            PairingPayload.decode("""{"v":"banana","url":"wss://h/acp","code":"ABCD2345"}"""),
        )
    }

    /**
     * This URL decides which host the phone POSTs its pairing request to, so a
     * scheme we do not recognise must never reach `PairingClient.pair`.
     */
    @Test
    fun `a URL whose scheme is not ws or wss is refused`() {
        listOf(
            "http://bridge.example.com/acp",
            "https://bridge.example.com/acp",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "wss:/only-one-slash/acp",
            "bridge.example.com/acp",
        ).forEach { url ->
            val result = PairingPayload.decode("""{"v":1,"url":"$url","code":"ABCD2345"}""")
            assertIs<DecodeResult.Malformed>(result, "expected $url to be refused")
        }
    }

    /** A hostless or userinfo-bearing URL is either useless or a redirection trick. */
    @Test
    fun `a URL with no host or with a username is refused`() {
        listOf("wss:///acp", "wss://", "wss://user@evil.example/acp", "wss://:8765/acp").forEach { url ->
            val result = PairingPayload.decode("""{"v":1,"url":"$url","code":"ABCD2345"}""")
            assertIs<DecodeResult.Malformed>(result, "expected $url to be refused")
        }
    }

    /** An IPv6 literal's colons are not a port separator; `adb reverse` aside, someone will try it. */
    @Test
    fun `an IPv6 literal is accepted`() {
        val result = PairingPayload.decode("""{"v":1,"url":"wss://[::1]:8765/acp","code":"ABCD2345"}""")

        assertIs<DecodeResult.Ok>(result)
    }

    /**
     * The bridge is the authority on whether a code is valid — `redeem` uppercases
     * and checks it. An app that hard-codes today's alphabet starts rejecting good
     * QRs the day a bridge widens it, and the app is the half that cannot be
     * updated in step.
     */
    @Test
    fun `a code is uppercased and trimmed, and an unfamiliar alphabet is still accepted`() {
        val lowercased = PairingPayload.decode("""{"v":1,"url":"wss://h/acp","code":"  8dd6yw6x "}""")
        assertEquals("8DD6YW6X", assertIs<DecodeResult.Ok>(lowercased).payload.code)

        val notTodaysAlphabet = PairingPayload.decode("""{"v":1,"url":"wss://h/acp","code":"IO01-9AZ"}""")
        assertIs<DecodeResult.Ok>(notTodaysAlphabet, "a widened bridge alphabet must not break an old app")
    }

    /**
     * The size gate has to run *before* the parser, so this payload is deliberately
     * one that would otherwise decode cleanly: `ignoreUnknownKeys` would swallow the
     * padding field and return Ok. Getting NotAPairingCode proves the gate came first.
     */
    @Test
    fun `an oversized payload is refused without being parsed`() {
        val padding = "x".repeat(PairingPayload.MAX_PAYLOAD_CHARS)
        val raw = """{"v":1,"url":"wss://h/acp","code":"ABCD2345","pad":"$padding"}"""

        assertEquals(DecodeResult.NotAPairingCode, PairingPayload.decode(raw))
    }

    /**
     * Every byte added here pushes the QR up a version and shortens the distance it
     * can be scanned from. 100 bytes keeps a realistic address inside a 33x33 code.
     */
    @Test
    fun `the encoded form stays small enough for a QR that scans across a desk`() {
        val encoded = PairingPayload.encode("wss://bridge.example.com/acp", "8DD6YW6X")

        assertTrue(encoded.length < 100, "payload grew to ${encoded.length} chars: $encoded")
    }
}
