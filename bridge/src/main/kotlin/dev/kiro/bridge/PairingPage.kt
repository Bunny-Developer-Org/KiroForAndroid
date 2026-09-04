package dev.kiro.bridge

import dev.kiro.core.auth.PairingPayload

/**
 * The `/qr` page.
 *
 * Top-level and `internal` for the same reason `PairingBanner` is: it is the only
 * way this can be tested, and the wording *is* the feature as far as a user is
 * concerned. Plain strings rather than a templating dependency — the whole document
 * is under a hundred lines and the version catalog stays untouched.
 *
 * No JavaScript anywhere. Rotation is server-side and the page re-fetches itself
 * with `<meta http-equiv="refresh">`, which is a real accessibility compromise
 * (WCAG 2.2 SC 2.2.1) accepted deliberately: a throttled background tab merely shows
 * a stale countdown and corrects on the next load, and the code is always printed as
 * text as well. A "hold this code" control would fix it properly, and would be
 * another form POST.
 */
internal object PairingPage {

    /** The live page: a QR, the address and code as text, and a countdown. */
    fun live(url: String, code: String, secondsLeft: Long, rotation: Int, email: String): String {
        val qr = QrSvg.forText(PairingPayload.encode(url, code)).orEmpty()
        return document(
            // Lands just after the code is due to change, so the picture on screen
            // is never one the bridge has already moved past.
            refreshAfter = secondsLeft + 1,
            body = """
                <p class="who">Signed in as <strong>${escape(email)}</strong></p>
                <div class="qr">$qr</div>
                <dl>
                  <dt>Address</dt><dd><code>${escape(url)}</code></dd>
                  <dt>Code</dt><dd><code class="code">${escape(code)}</code></dd>
                </dl>
                <p class="countdown">A new code appears in about ${secondsLeft}s.</p>
                <p>
                  The code changes every ${QrPageBudget.ROTATE_SECONDS}s, so a QR someone photographed — or saw
                  in a screen share — stops working almost immediately. The one it replaces keeps
                  working for ${PairingService.SUPERSEDED_GRACE_SECONDS}s more, so a scan that lands just as the picture
                  changes still pairs.
                </p>
                <p>Open KiroForAndroid on the phone, choose <strong>Add a bridge</strong>, then <strong>Scan</strong>.</p>
                <details>
                  <summary>Opened this on the phone you want to pair?</summary>
                  <p>
                    Then you cannot scan your own screen. Type the address and code above into the
                    app's manual entry instead — it is the same pairing, and the code is built to be
                    typed by hand (no I, O, 0 or 1). It changes with the picture, so type it soon.
                  </p>
                </details>
                <p class="fine">
                  Code $rotation of ${QrPageBudget.MAX_ROTATIONS} in this session. Served only to a browser that passed
                  Cloudflare Access. It mints pairing codes, so it stops on its own after
                  ${QrPageBudget.SESSION_MAX_SECONDS / SECONDS_PER_MINUTE} minutes.
                </p>
            """.trimIndent(),
        )
    }

    /** No refresh here, deliberately: a stopped page that refreshed would bound nothing. */
    fun stopped(rotations: Int): String = document(
        refreshAfter = null,
        body = """
            <h2>Paused</h2>
            <p>
              This page has shown $rotations codes over the last
              ${QrPageBudget.SESSION_MAX_SECONDS / SECONDS_PER_MINUTE} minutes and has stopped minting more. A pairing page
              left open is a code being minted every ${QrPageBudget.ROTATE_SECONDS} seconds for as long as the tab
              exists, and this bridge would rather stop than do that unattended.
            </p>
            <p>Nothing is broken, and nothing already paired is affected.</p>
            ${resumeForm("Show codes for another ${QrPageBudget.SESSION_MAX_SECONDS / SECONDS_PER_MINUTE} minutes")}
            <p class="fine">
              Or run <code>kiro-bridge pair</code> on the bridge host, which prints the same code and
              QR in a terminal.
            </p>
        """.trimIndent(),
    )

    /**
     * How an open tab stops in the successful case.
     *
     * "A device paired", not "your phone paired": if someone ran `kiro-bridge pair`
     * in the same window this names the wrong device, and the weaker claim reads as
     * information rather than as a lie.
     */
    fun paired(deviceName: String): String = document(
        refreshAfter = null,
        body = """
            <h2>Paired</h2>
            <p>A device paired: <strong>${escape(deviceName)}</strong>. You can close this page.</p>
            ${resumeForm("Pair another phone")}
        """.trimIndent(),
    )

    /** A bridge that cannot name its own address has nothing honest to put in a QR. */
    fun noAddress(advisory: String): String = document(
        refreshAfter = null,
        body = """
            <h2>This bridge has no address to advertise</h2>
            <pre>${escape(advisory)}</pre>
        """.trimIndent(),
    )

    private fun resumeForm(label: String) =
        """<form method="post" action="/qr"><button type="submit">${escape(label)}</button></form>"""

    private fun document(refreshAfter: Long?, body: String): String {
        val refresh = refreshAfter?.let { "\n  <meta http-equiv=\"refresh\" content=\"$it\">" } ?: ""
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">$refresh
              <title>Pair a phone · kiro-bridge</title>
              <style>$STYLE</style>
            </head>
            <body>
            <main>
            <h1>Pair a phone</h1>
            $body
            </main>
            </body>
            </html>
        """.trimIndent()
    }

    /** Everything interpolated is operator- or identity-supplied, so all of it is escaped. */
    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private const val SECONDS_PER_MINUTE = 60

    // The QR itself is black-on-white in both schemes; see QrSvg for why inverting
    // it is a code that never scans rather than a styling preference.
    private val STYLE = """
        :root { color-scheme: light dark; --fg: #18181b; --bg: #fafafa; --muted: #6b7280; --line: #e4e4e7; }
        @media (prefers-color-scheme: dark) {
          :root { --fg: #e4e4e7; --bg: #12141a; --muted: #9ca3af; --line: #27272a; }
        }
        body { margin: 0; background: var(--bg); color: var(--fg);
               font: 15px/1.55 system-ui, -apple-system, "Segoe UI", sans-serif; }
        main { max-width: 34rem; margin: 0 auto; padding: 2rem 1rem 4rem; }
        h1 { font-size: 1.5rem; margin: 0 0 .25rem; }
        h2 { font-size: 1.2rem; }
        .who { color: var(--muted); margin: 0 0 1.5rem; }
        .qr { background: #fff; padding: 12px; border-radius: 10px; width: fit-content; margin: 0 auto 1.5rem; }
        .qr svg { display: block; width: 260px; height: 260px; }
        dl { display: grid; grid-template-columns: auto 1fr; gap: .35rem 1rem; margin: 0 0 .75rem; }
        dt { color: var(--muted); }
        dd { margin: 0; }
        code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; word-break: break-all; }
        .code { font-size: 1.3rem; letter-spacing: .12em; }
        .countdown { color: var(--muted); margin-top: 0; }
        .fine { color: var(--muted); font-size: .82rem; border-top: 1px solid var(--line);
                padding-top: 1rem; margin-top: 2rem; }
        details { border: 1px solid var(--line); border-radius: 8px; padding: .6rem .9rem; }
        summary { cursor: pointer; }
        button { font: inherit; padding: .6rem 1rem; border-radius: 8px; border: 1px solid var(--line);
                 background: var(--fg); color: var(--bg); cursor: pointer; }
        pre { white-space: pre-wrap; }
    """.trimIndent()
}
