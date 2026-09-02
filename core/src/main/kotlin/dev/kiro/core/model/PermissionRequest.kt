package dev.kiro.core.model

/**
 * An agent asking to do something that needs a human. The highest-stakes object
 * in the app.
 *
 * [options] is rendered as sent, keyed by [PermissionOption.kind]. The four Kiro
 * 2.19.2 offers are not a contract — the list is agent-supplied, and hard-coding
 * it means a new option silently disappears from the UI (ACP-INTEGRATION §6).
 */
public data class PermissionRequest(
    val sessionId: String,
    val toolCallId: String,
    val title: String?,
    val options: List<PermissionOption>,
    val consent: Consent?,
    /**
     * The JSON-RPC id of the originating `session/request_permission`, when this
     * arrived as a server-initiated request on a connection we still hold.
     *
     * Null when the request was reconstructed from a `pending_interaction` update
     * or from a push notification — which is the normal mobile case. Answering
     * then goes through `_kiro/permission/respond`, correlated by [toolCallId].
     */
    val rpcId: Long?,
) {
    /**
     * The one-line summary a notification has to fit, and the accessible label the
     * card needs regardless (VISUAL-LANGUAGE §7). Built from consent metadata when
     * present because "Run `id -un`" is readable and "permission request" is not.
     */
    public val summary: String
        get() = when {
            consent?.resource != null -> consent.resource
            !title.isNullOrBlank() -> title
            else -> "The agent is asking for permission"
        }

    public data class Consent(
        val capability: String?,
        val resource: String?,
        val askType: String?,
        val workspaceRoot: String?,
    ) {
        /** True when the agent asked without the user having invited the action. */
        val isImplicit: Boolean get() = askType == "implicit"
    }
}

public data class PermissionOption(
    val optionId: String,
    val name: String,
    val kind: Kind,
) {
    public enum class Kind(public val wire: String) {
        ALLOW_ONCE("allow_once"),
        ALLOW_ALWAYS("allow_always"),
        REJECT_ONCE("reject_once"),
        REJECT_ALWAYS("reject_always"),

        /** An option this build has never heard of. Still rendered, still tappable. */
        UNKNOWN(""),
        ;

        public val isAllow: Boolean get() = this == ALLOW_ONCE || this == ALLOW_ALWAYS
        public val isReject: Boolean get() = this == REJECT_ONCE || this == REJECT_ALWAYS

        /** True for the two options whose effect outlives this one request. */
        public val isStanding: Boolean get() = this == ALLOW_ALWAYS || this == REJECT_ALWAYS

        public companion object {
            public fun fromWire(value: String?): Kind =
                entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

/**
 * The second human-in-the-loop channel: the agent asking a free-text question
 * mid-turn (`_kiro/userInput`). Undocumented, found by F-01, and distinct from a
 * permission request — it is a question, not an authorisation.
 */
public data class UserInputRequest(
    val sessionId: String,
    val toolCallId: String,
    val question: String,
    val placeholder: String?,
)

public sealed interface InteractionOutcome {
    public data class Selected(val optionId: String) : InteractionOutcome
    public data object Cancelled : InteractionOutcome
}
