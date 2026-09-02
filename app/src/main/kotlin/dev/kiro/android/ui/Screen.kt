package dev.kiro.android.ui

import dev.kiro.core.model.CloudSession

/**
 * Where the screens meet the gateway.
 *
 * Deliberately a small sealed hierarchy plus a `when` rather than
 * `navigation-compose` routes: the graph is three destinations and one of them
 * carries a live object. A route-and-argument indirection would buy type-safe
 * deep links this app has no use for yet, and cost the ability to hand a screen
 * the session it is already holding.
 */
sealed interface Screen {
    data object Sessions : Screen

    /**
     * [prefillRepos] is how "New session in this repo" works from the transcript
     * header (F-11/ADR-004 §5): repositories cannot be added to a *running*
     * session, so the only "switch" is starting a fresh one pre-seeded with the
     * old session's repos rather than presenting a set that looks editable.
     */
    data class Create(val prefillRepos: List<String> = emptyList()) : Screen

    data class Transcript(val session: CloudSession) : Screen
}
