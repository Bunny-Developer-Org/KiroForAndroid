package dev.kiro.android.ui.onboarding

/**
 * A pairing QR that was scanned and decoded, on its way to the pairing form.
 *
 * [id] carries identity that [url] and [code] do not, and it is load-bearing:
 * re-scanning the *same* code after a failed attempt has to trigger the pairing
 * again, and two equal payloads would compare equal and be swallowed by the
 * `LaunchedEffect` that watches this.
 */
data class ScannedPairing(val url: String, val code: String, val id: Long)
