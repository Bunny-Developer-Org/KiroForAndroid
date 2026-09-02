package dev.kiro.core.session

import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.model.UserInputRequest
import kotlinx.coroutines.flow.Flow

/**
 * The seam every feature codes against.
 *
 * Nothing above this interface may reference a transport, a socket, or the bridge
 * — that is what makes ADR-001's topology decision reversible. If Kiro ever
 * publishes a third-party API, it becomes a second implementation of this
 * interface rather than a rewrite of the app.
 */
public interface CloudSessionGateway {

    /** Connection state, so the UI can name what is wrong instead of spinning. */
    public val connection: Flow<ConnectionState>

    /**
     * Every streaming update, for every session this gateway is watching.
     *
     * A hot flow: subscribing does not replay. Transcript state comes from
     * [loadSession] plus this; see [TranscriptReducer].
     */
    public val updates: Flow<SessionUpdate>

    /** Permission requests, from any source — live socket, replay, or reattach. */
    public val permissionRequests: Flow<PermissionRequest>

    /** The agent's second human-in-the-loop channel: a free-text question. */
    public val userInputRequests: Flow<UserInputRequest>

    /** Roster changes pushed by the agent, so the session list need not poll. */
    public val rosterChanges: Flow<RosterChange>

    public suspend fun listSessions(
        source: SessionSource = SessionSource.REMOTE,
        scope: ListScope = ListScope.USER,
    ): List<CloudSession>

    /**
     * Creates a session.
     *
     * Repositories are bound here and cannot be changed afterwards; branches cannot
     * be selected at all. Both constraints belong in the UI copy rather than in a
     * picker that silently does nothing (ADR-004).
     */
    public suspend fun createSession(request: CreateSessionRequest): CloudSession

    /**
     * Attaches to an existing session. The agent replays the full history as
     * updates — F-01 measured 991 of them on one real cloud session, so this is a
     * streaming operation, not a fetch.
     */
    public suspend fun loadSession(sessionId: String, source: SessionSource = SessionSource.REMOTE)

    public suspend fun prompt(sessionId: String, blocks: List<PromptBlock>): String?

    public suspend fun cancel(sessionId: String)

    public suspend fun setMode(sessionId: String, modeId: String)

    public suspend fun setModel(sessionId: String, modelId: String)

    /**
     * Answers a permission request.
     *
     * Deliberately keyed by `toolCallId` rather than by the originating JSON-RPC
     * id: KAS correlates on the tool call, which means an approval can be answered
     * on a connection that never saw the request. That is exactly the mobile case
     * — a notification arrives, the socket has long since dropped, the user taps
     * Allow on a fresh one.
     */
    public suspend fun respondToPermission(sessionId: String, toolCallId: String, optionId: String)

    public suspend fun respondToUserInput(
        sessionId: String,
        toolCallId: String,
        answer: String?,
    )

    public suspend fun deleteSession(sessionId: String)

    public suspend fun listSourceProviders(): List<SourceProvider>

    public suspend fun listRepositories(providerType: String): List<RepoCandidate>

    public suspend fun disconnect()
}

/**
 * What the app is allowed to say about the connection.
 *
 * Every state names a cause, because ADR-005 §5.3 makes "a named state rather
 * than a spinner" acceptance criteria: the backend is someone's machine, and
 * "unreachable" is a normal condition with a normal explanation, not an error.
 */
public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data object Connecting : ConnectionState

    public data class Connected(
        val agentSupportsCloudSessions: Boolean,
        val supportsImages: Boolean,
    ) : ConnectionState

    /** Reachable, but not talking to us — bad token, TLS failure, protocol mismatch. */
    public data class Rejected(val reason: String) : ConnectionState

    /**
     * The bridge did not answer. [lastSeenMillis] drives the honest copy —
     * "bridge unreachable, last seen 3h ago" — and [onlyBridgeIsWorkstation] is
     * what lets the app add "the machine is probably asleep" rather than implying
     * a fault.
     */
    public data class Unreachable(
        val lastSeenMillis: Long?,
        val onlyBridgeIsWorkstation: Boolean,
    ) : ConnectionState

    public data class Reconnecting(val attempt: Int, val nextRetryMillis: Long) : ConnectionState
}

public data class CreateSessionRequest(
    val repositories: List<String>,
    val firstPrompt: String,
    val modeId: String? = null,
    val modelId: String? = null,
)

public sealed interface PromptBlock {
    public data class Text(val text: String) : PromptBlock

    /** The harness advertises `promptCapabilities.image`; on a phone that matters. */
    public data class Image(val mimeType: String, val base64Data: String) : PromptBlock
}

public data class RosterChange(
    val upserted: List<CloudSession>,
    val deleted: List<String>,
)

/** Raised when the agent is reachable but cannot serve cloud sessions. */
public class CloudUnavailableException(message: String) : Exception(message)

/**
 * The account is signed in but not entitled to cloud sessions.
 *
 * This has to be derived from a failed create rather than probed in advance:
 * F-01 established `whoami --format json` returns identity only, with no plan or
 * entitlement field. Without this distinction an unentitled user gets an opaque
 * failure and no idea that the answer is "upgrade to Pro".
 */
public class NotEntitledException(message: String) : Exception(message)

/** The preview caps concurrent cloud sessions at 10. Worth its own message. */
public class SessionLimitReachedException(message: String) : Exception(message)
