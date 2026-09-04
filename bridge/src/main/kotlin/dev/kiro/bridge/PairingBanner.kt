package dev.kiro.bridge

import dev.kiro.core.auth.PairingPayload

/**
 * What an operator reads when it is time to pair a phone.
 *
 * Top-level and `internal` rather than private to `Main.kt`, which is the only
 * reason it can be tested at all -- and the wording here is the whole feature as
 * far as a user is concerned.
 *
 * The QR comes first so that someone already holding a phone up to the screen does
 * not have to scroll, and the address and code follow **always**, whether or not a
 * QR could be drawn. A terminal that mangles the block glyphs, a `--no-qr`, an
 * address too wide to draw: none of them may leave the operator without a way in.
 *
 * Built from loose values rather than a [BridgeConfig] because `bridge pair` prints
 * the same banner from a *different process*, using an address and advisory that
 * came back over the control socket from the bridge that actually knows them.
 */
@Suppress("LongParameterList")
internal fun pairingBanner(
    url: String?,
    code: String,
    ttlSeconds: Long,
    advisory: String?,
    authNote: String? = null,
    footer: String? = null,
    printQr: Boolean = true,
    color: Boolean = true,
): String = buildString {
    val qr = if (printQr && url != null) TerminalQr.forText(PairingPayload.encode(url, code), color) else null

    appendLine()
    appendLine("  Pair this bridge with your phone")
    appendLine("  ────────────────────────────────")
    if (qr != null) {
        qr.lineSequence().forEach { appendLine("  $it") }
        appendLine()
    }
    appendLine("  Address : ${url ?: "not known -- see below"}")
    appendLine("  Code    : $code   (valid for ${ttlSeconds / SECONDS_PER_MINUTE} minutes, single use)")
    appendLine()

    advisory?.lineSequence()?.forEach { appendLine("  $it") }
    authNote?.let { appendLine("  Auth    : $it") }
    footer?.let {
        appendLine()
        appendLine("  $it")
    }
}

internal fun pairingBanner(config: BridgeConfig, code: String, color: Boolean, footer: String? = null): String =
    pairingBanner(
        url = config.advertisedUrl(),
        code = code,
        ttlSeconds = PairingService.CODE_TTL_SECONDS,
        advisory = pairingAdvisory(config),
        authNote = if (config.apiKey != null) "KIRO_API_KEY (this host's key, not a per-user sign-in)." else null,
        footer = footer,
        printQr = config.printQr,
        color = color,
    )

/**
 * The one thing most likely to be wrong about a real deployment, said out loud.
 *
 * Authored here and returned over the control socket verbatim, so `bridge pair`
 * run from an SSH session says exactly what the startup banner says rather than
 * re-deriving it from an argv it does not have.
 *
 * @return null when the bridge is advertising an address that can actually work.
 */
internal fun pairingAdvisory(config: BridgeConfig): String? = when {
    config.publicUrl != null -> null

    config.advertisedUrl() == null ->
        """
        This bridge binds ${config.bindAddress}, which names no single address a phone
        can use, so there is no QR to draw. Pass --public-url wss://your-hostname/acp
        (or set KIRO_BRIDGE_PUBLIC_URL) to say how a phone reaches this bridge.
        """.trimIndent()

    config.isLoopbackOnly ->
        """
        This bridge is bound to loopback, so the address above only works from a
        phone that reaches this machine on loopback -- an emulator, or the
        `adb reverse` that tools/run-on-device.sh sets up.

        A bridge reached through a tunnel binds loopback too, but its phone does
        not: pass --public-url wss://your-hostname/acp (or set
        KIRO_BRIDGE_PUBLIC_URL), or the QR above will send the phone to itself.
        """.trimIndent()

    else -> null
}

private const val SECONDS_PER_MINUTE = 60
