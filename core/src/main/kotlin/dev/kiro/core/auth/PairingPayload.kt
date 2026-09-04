package dev.kiro.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull

/**
 * What the bridge puts in a QR code and the app reads back out of one.
 *
 * This lives in `core/` for one reason: the bridge encodes it and the app decodes
 * it, and the two must not be able to drift. A format defined twice is a format
 * that disagrees with itself on the day one side gains a field. It is also the
 * only way any of this gets tested — `app/` has no Robolectric and no
 * instrumentation, so logic that must be verified cannot live there.
 *
 * The payload carries the bridge's address and a pairing code, and **nothing
 * else**. No device token, no API key: a QR is photographed, screenshotted and
 * pasted into chat windows, and AUTHENTICATION §4 makes the code single-use and
 * short-lived precisely so that its escape is survivable.
 *
 * @see decode for what happens when a user points the camera at a Wi-Fi QR.
 */
@Serializable
public data class PairingPayload(
    @SerialName("v") val version: Int,
    @SerialName("url") val url: String,
    @SerialName("code") val code: String,
) {

    /** What [decode] made of some scanned text. */
    public sealed interface DecodeResult {

        public data class Ok(val payload: PairingPayload) : DecodeResult

        /**
         * Structurally not ours at all — a URL, a Wi-Fi credential, a vCard.
         *
         * Distinct from [Malformed] because the two say different things to a
         * user: "that isn't a Kiro code" versus "that is a Kiro code and it is
         * broken." Only one of them suggests the bridge is at fault.
         */
        public data object NotAPairingCode : DecodeResult

        /** Ours, but from a bridge newer than this app. */
        public data class UnsupportedVersion(val version: Int) : DecodeResult

        /** Ours, and this version, but the contents cannot be trusted. */
        public data class Malformed(val reason: String) : DecodeResult
    }

    public companion object {

        /**
         * Bumped only when [url] or [code] stop being readable by an older app.
         *
         * Additive fields do not need it: [PairingJson] ignores unknown keys, which
         * is the tolerant-parsing rule ADR-003 §3 applies project-wide. That is
         * also why [decode] refuses a *higher* version outright rather than
         * reading what it recognises — past this number, "url" and "code" are no
         * longer promises, and honouring them would be guessing.
         */
        public const val VERSION: Int = 1

        /** No I, O, 0 or 1 — this gets read aloud and typed in by hand. */
        public const val CODE_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        public const val CODE_LENGTH: Int = 8

        /**
         * Refuse anything larger without parsing it.
         *
         * A QR can legally carry ~2953 bytes, all of them chosen by whoever
         * printed it. A real payload is under 100 (see the test that pins this),
         * so anything approaching this limit is not a payload we truncated — it is
         * someone else's data, and the JSON parser should never see it.
         */
        public const val MAX_PAYLOAD_CHARS: Int = 512

        private const val MAX_URL_CHARS = 256
        private const val CODE_MIN_LENGTH = 4
        private const val CODE_MAX_LENGTH = 32
        private const val PRINTABLE_ASCII_MIN = ' '.code + 1
        private const val PRINTABLE_ASCII_MAX = '~'.code

        /**
         * Note `encodeDefaults = true`, and do not "simplify" this to `AcpJson`.
         *
         * `AcpJson` sets `encodeDefaults = false` (JsonRpc.kt). Under it, a
         * `version` field carrying its default would be silently omitted from
         * every QR the bridge prints, and every app would then read those QRs as
         * [DecodeResult.NotAPairingCode] — a total, silent failure of the feature
         * with a green build behind it. `version` also deliberately has no default
         * for the same reason.
         */
        private val PairingJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            isLenient = false
        }

        /** The exact text the bridge renders into a QR. */
        public fun encode(url: String, code: String): String =
            PairingJson.encodeToString(PairingPayload(VERSION, url, code))

        /**
         * Read scanned text, refusing anything this app cannot safely act on.
         *
         * The order of the checks below is the contract, because each outcome
         * produces a different message and a different next step for the user.
         * A camera pointed at the world will meet far more non-payloads than
         * payloads, so "not ours" is a normal result here, not an error.
         */
        public fun decode(raw: String): DecodeResult {
            val text = raw.trim()
            if (text.length > MAX_PAYLOAD_CHARS || !text.startsWith("{")) {
                return DecodeResult.NotAPairingCode
            }

            val root = runCatching { PairingJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return DecodeResult.NotAPairingCode

            // The version is read off the raw object and settled *before* the
            // strict deserializer runs, and the order is the whole point. A version
            // bump means `url` or `code` stopped being readable (see VERSION), so
            // decoding first would throw on the missing field and report a newer
            // bridge's QR as "not a pairing code at all" -- sending the user off to
            // rescan a code that will never work, instead of telling them to update.
            val version = (root["v"] as? JsonPrimitive)?.takeIf { it.intOrNull != null }?.int
                ?: return DecodeResult.NotAPairingCode
            if (version < VERSION) return DecodeResult.Malformed("$version is not a version")
            if (version > VERSION) return DecodeResult.UnsupportedVersion(version)

            val payload = runCatching { PairingJson.decodeFromJsonElement(serializer(), root) }.getOrNull()
                ?: return DecodeResult.NotAPairingCode

            validateUrl(payload.url)?.let { return DecodeResult.Malformed(it) }

            val code = payload.code.trim().uppercase()
            validateCode(code)?.let { return DecodeResult.Malformed(it) }

            return DecodeResult.Ok(payload.copy(code = code))
        }

        /**
         * The security-relevant half of [decode], and the reason it is hand-rolled.
         *
         * This string decides which host the phone will POST a pairing request to
         * (`PairingClient.pair` rewrites the scheme and calls it), so a scheme
         * this does not recognise must never reach that call. `java.net.URI` is
         * both JVM-only — the purity rule in ADR-003 §2 exists to keep a KMP
         * target a refactor — and far too willing to find meaning in odd input,
         * which is the opposite of what a validator wants.
         *
         * @return a human-readable reason, or null when the URL is acceptable.
         */
        private fun validateUrl(url: String): String? {
            if (url.length > MAX_URL_CHARS) return "the address is too long"
            if (url.any { it.isWhitespace() || it.isISOControl() }) {
                return "the address contains whitespace or control characters"
            }

            val lower = url.lowercase()
            val authority = when {
                lower.startsWith("wss://") -> url.substring("wss://".length)
                lower.startsWith("ws://") -> url.substring("ws://".length)
                else -> return "the address is not ws:// or wss://"
            }.takeWhile { it != '/' && it != '?' && it != '#' }

            if ('@' in authority) return "the address carries a username"
            return if (hostOf(authority).isEmpty()) "the address has no host" else null
        }

        /** Splits a trailing `:port` off, leaving an IPv6 literal's colons alone. */
        private fun hostOf(authority: String): String =
            if (authority.startsWith("[")) {
                authority.substringBefore(']').removePrefix("[")
            } else {
                authority.substringBefore(':')
            }

        /**
         * Deliberately looser than [CODE_ALPHABET].
         *
         * The bridge is the authority on whether a code is valid; this only checks
         * that it is plausibly a code at all. An app that hard-codes the alphabet
         * starts rejecting perfectly good QRs the day a bridge widens it, and the
         * app in the field is the half that cannot be updated in step.
         *
         * @return a human-readable reason, or null when the code is acceptable.
         */
        private fun validateCode(code: String): String? = when {
            code.length !in CODE_MIN_LENGTH..CODE_MAX_LENGTH -> "the code is the wrong length"
            code.any { it.code !in PRINTABLE_ASCII_MIN..PRINTABLE_ASCII_MAX } ->
                "the code contains characters a bridge would not print"
            else -> null
        }
    }
}
