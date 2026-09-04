package dev.kiro.bridge

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.kiro.bridge.QrRoutes")

/** The header Cloudflare Access puts the signed assertion in. */
private const val ACCESS_ASSERTION_HEADER = "Cf-Access-Jwt-Assertion"

/**
 * `GET /qr` — a pairing page a phone can be paired from with no SSH at all.
 *
 * Its own file rather than more lines in `BridgeServer.installRoutes`, which is
 * already close to detekt's `LongMethod` threshold.
 *
 * **Only this route is Access-gated, and that is not an oversight.** `/`, `/pair`
 * and `/acp` must stay open to a caller with no browser session: the Android app
 * cannot complete an interactive Google sign-in, so an Access application scoped to
 * the whole hostname stops the phone pairing *and* stops it connecting, with an
 * error that points nowhere near Cloudflare. `QrPageTest` pins this.
 */
internal fun Route.installQrRoutes(
    config: BridgeConfig,
    pairing: PairingService,
    verifier: AccessVerifier?,
    budget: QrPageBudget,
) {
    get("/qr") {
        val email = call.verifiedEmail(config, verifier) ?: return@get
        call.securityHeaders()

        val url = config.advertisedUrl()
        if (url == null) {
            // Nothing to put in a QR, so mint nothing -- a code with no address is
            // useless, and issuing one to display would burn the budget for nothing.
            // The advisory is the banner's, verbatim, so the page and the terminal
            // say identical words about an identical misconfiguration.
            call.respondText(PairingPage.noAddress(pairingAdvisory(config).orEmpty()), ContentType.Text.Html)
            return@get
        }

        val devices = pairing.listDevices()
        val decision = budget.next(email, devices.size) {
            pairing.issueCode(PairingService.CodeSource.QR_PAGE)
        }
        val page = when (decision) {
            is QrPageBudget.Decision.Paired ->
                PairingPage.paired(devices.lastOrNull()?.name ?: "a new device")
            is QrPageBudget.Decision.Stopped -> PairingPage.stopped(decision.rotations)
            is QrPageBudget.Decision.Live ->
                PairingPage.live(url, decision.code, decision.secondsUntilRotation, decision.rotation, email)
        }
        call.respondText(page, ContentType.Text.Html)
    }

    // A form POST, not a link: a POST cannot be fired by the meta refresh, by a link
    // prefetcher, or by a session restore. The bound is only worth having if the
    // thing that lifts it is unambiguously a human clicking a button.
    post("/qr") {
        val email = call.verifiedEmail(config, verifier) ?: return@post

        // ...but "unambiguously a human" also has to mean "a human on *this* page".
        // Cloudflare Access issues its session cookie SameSite=None, and the edge
        // turns that cookie into the assertion header, so a hostile page an operator
        // merely visits can auto-submit this form cross-origin with a live session
        // attached. Looping reset+GET would then mint a code every 30 seconds
        // indefinitely -- exactly the state QrPageBudget exists to prevent -- and
        // continuously shorten the code the operator is actually looking at.
        // X-Frame-Options stops the response rendering, not the request arriving.
        if (!call.isSameOrigin()) {
            log.warn("refusing a cross-origin POST /qr")
            call.refuse(REJECTED)
            return@post
        }

        budget.reset(email)
        call.securityHeaders()
        // 303 See Other, not Ktor's default 302: post/redirect/get is the point, and
        // only 303 tells the browser to *GET* the target. A 302 leaves re-submitting
        // the POST technically permissible, which is the reload prompt this avoids.
        call.response.header("Location", "/qr")
        call.respond(HttpStatusCode.SeeOther)
    }
}

/**
 * @return the verified email, or null having already answered 403.
 */
private suspend fun ApplicationCall.verifiedEmail(config: BridgeConfig, verifier: AccessVerifier?): String? {
    if (verifier == null || !config.accessEnabled) {
        refuse(NOT_CONFIGURED)
        return null
    }
    val assertion = request.header(ACCESS_ASSERTION_HEADER)
    if (assertion.isNullOrBlank()) {
        refuse(NO_ASSERTION)
        return null
    }
    return when (val result = verifier.verify(assertion)) {
        is AccessVerifier.Result.Verified -> result.email
        is AccessVerifier.Result.Rejected -> {
            // The reason goes to the log and nowhere else. Telling an unauthenticated
            // caller "wrong audience" rather than "bad signature" is a free oracle
            // about the Access configuration.
            log.warn("refusing a /qr request: {}", result.reason)
            refuse(REJECTED)
            null
        }
    }
}

/**
 * Whether a state-changing request came from this bridge's own page.
 *
 * `Origin` is set by the browser and cannot be forged from script, and it *is* sent
 * on cross-origin form posts -- which is precisely the case being refused. A request
 * with no `Origin` at all is refused too: every browser that can submit this form
 * sends one, so its absence means something that is not that form.
 */
private fun ApplicationCall.isSameOrigin(): Boolean {
    val origin = request.header("Origin") ?: return false
    val host = request.header("Host") ?: return false
    return origin.substringAfter("://").equals(host, ignoreCase = true)
}

private suspend fun ApplicationCall.refuse(body: String) {
    securityHeaders()
    respondText(body, ContentType.Text.Plain, HttpStatusCode.Forbidden)
}

private fun ApplicationCall.securityHeaders() {
    // A pairing code in a browser or intermediary cache is exactly the leak rotation
    // exists to prevent; DENY because the resume button is a clickjacking surface;
    // and the CSP hard-forbids script on a page that deliberately contains none.
    response.header("Cache-Control", "no-store")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("X-Frame-Options", "DENY")
    response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:")
}

private val NOT_CONFIGURED = """
    /qr is not enabled on this bridge.

    It serves a rotating pairing QR to a browser that has passed Cloudflare Access,
    and it will not serve one to anything else. Enabling it takes two values from
    your Cloudflare Zero Trust dashboard:

      --access-team-domain <your-team>.cloudflareaccess.com
          (or KIRO_BRIDGE_ACCESS_TEAM_DOMAIN)
      --access-aud <the application's Application Audience (AUD) tag>
          (or KIRO_BRIDGE_ACCESS_AUD)

    Scope the Access application to the /qr path, not the whole hostname: the phone
    cannot complete a browser sign-in, so gating /pair and /acp would break pairing
    and the session socket.

    Until then, `kiro-bridge pair` on the bridge host prints the same QR.

""".trimIndent()

private val NO_ASSERTION = """
    This request did not come through Cloudflare Access.

    /qr is served only to a browser carrying a Cf-Access-Jwt-Assertion header that
    this bridge has verified against your team's published signing keys. Reaching the
    bridge's port directly -- over the LAN, or an SSH tunnel -- skips Access, and
    skipping Access is what this route refuses.

    Open https://<your bridge hostname>/qr instead.

""".trimIndent()

private val REJECTED = """
    Cloudflare Access did not vouch for this request.

    The bridge log says why. Signing in again usually fixes it: an Access session
    that has expired looks exactly like this.

""".trimIndent()
