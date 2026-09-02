package dev.kiro.core.model

/** A repository bound to a session, as the roster reports it. */
public data class SourceRepo(
    val providerType: String,
    val name: String,
    val url: String?,
)

/**
 * A repository offered by the catalog, which carries more than a bound one does.
 *
 * [defaultBranch] is shown as information only. Branches cannot be selected at
 * creation or attach time in the cloud-session preview, so an app that renders a
 * branch picker here is promising something the protocol cannot deliver
 * (ADR-004 §5).
 */
public data class RepoCandidate(
    val providerType: String,
    val name: String,
    val url: String?,
    val visibility: String?,
    val defaultBranch: String?,
) {
    val isPrivate: Boolean get() = visibility == "private"
}

/**
 * A source provider and whether the user's Kiro account has actually connected it.
 *
 * The connection status is why this type exists rather than a bare list of repos:
 * an empty repository list because GitLab was never connected needs to say
 * "connect GitLab first", not render as "you have no repositories" (F-11).
 */
public data class SourceProvider(
    val providerType: String,
    val displayName: String?,
    val connectionStatus: ConnectionStatus,
) {
    public enum class ConnectionStatus {
        CONNECTED,
        NOT_CONNECTED,
        UNKNOWN,
        ;

        public companion object {
            public fun fromWire(value: String?): ConnectionStatus = when (value) {
                "connected" -> CONNECTED
                "not_connected", "notConnected" -> NOT_CONNECTED
                else -> UNKNOWN
            }
        }
    }
}
