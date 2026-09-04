package dev.kiro.android.ui.onboarding

import dev.kiro.core.auth.PairingPayload.DecodeResult

/**
 * What to say when a scan did not produce a pairing.
 *
 * A pure function in its own file for the same reason `ModelBar`'s formatting is:
 * this app has no Compose test runtime and no instrumentation, so wording that
 * matters gets decided outside the composable where it can be asserted on.
 *
 * Every message names manual entry, without exception. A camera pointed at the
 * world will meet far more non-payloads than payloads, and none of those moments
 * may end in a dead end -- the form below the button always works.
 *
 * @return null when there is nothing to say, i.e. the scan succeeded.
 */
internal fun scanErrorMessage(result: DecodeResult): String? = when (result) {
    is DecodeResult.Ok -> null

    DecodeResult.NotAPairingCode ->
        "That isn't a Kiro bridge pairing code. Scan the QR the bridge prints when " +
            "it starts, or run `kiro-bridge pair` on the bridge host to print a new " +
            "one — or enter the address and code below by hand."

    is DecodeResult.UnsupportedVersion ->
        "That bridge is newer than this app, so its QR can't be read here. Update " +
            "the app, or enter the address and code below by hand — that still works."

    is DecodeResult.Malformed ->
        "That pairing code could not be read (${result.reason}). Run `kiro-bridge " +
            "pair` on the bridge host for a fresh one, or enter the address and code " +
            "below by hand."
}
