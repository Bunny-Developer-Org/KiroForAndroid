package dev.kiro.core.session

import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.KiroModel
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.ModelSelection
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.model.UserInputRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    /**
     * Which model each session is using, and what it could use instead.
     *
     * A `StateFlow`, unlike the event streams above, because this is *state*: a
     * screen that opens mid-session must be able to read the current value rather
     * than wait for the next push. It updates from every source the agent offers
     * — the `session/new` and `session/load` results, every `config_option_update`
     * notification, and the `session/set_config_option` response.
     *
     * For a cloud session it starts at [ModelSelection.Unknown] and stays there
     * until the sandbox pushes its first `config_option_update`; that gap is real
     * and the UI must render it as "not known yet" (PROTOCOL-FINDINGS §4d).
     *
     * Defaulted to "nothing known" so a stub gateway — a test double, a screen's
     * fake — is not forced to have an opinion about models it never sees.
     */
    public val models: Flow<ModelState> get() = flowOf(ModelState())

    /** The last known selection for one session, or [ModelSelection.Unknown]. */
    public fun modelsFor(sessionId: String): ModelSelection = ModelSelection.Unknown

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

    /**
     * Switches the model a session uses from the next message onwards.
     *
     * [modelId] is a wire id from [ModelSelection.available] — `auto`,
     * `claude-opus-5`, `gpt-5.6-luna` and so on. Sent as
     * `session/set_config_option` rather than `session/set_model`: the latter is
     * ACP-standard but unimplemented by KAS, which answers method-not-found
     * (PROTOCOL-FINDINGS §4d).
     *
     * Throws whatever the agent returned if the switch was refused — a caller
     * that swallows this will show a model the session is not using.
     */
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
    /** Sent as `_meta.kiro.modeId`; the agent defaults to `vibe` when null. */
    val modeId: String? = null,
    /**
     * Applied *after* the session exists, with [CloudSessionGateway.setModel].
     *
     * `session/new` does accept a `_meta.kiro.modelId`, but only on the local
     * path — KAS's cloud create passes the backend `agentMode`, execution target
     * and repositories and drops the model outright (PROTOCOL-FINDINGS §4d). So a
     * requested model has to be a second call, and it is best-effort: a session
     * that was created but could not be switched is still a session, and the
     * gateway reports the model the agent actually confirms rather than the one
     * that was asked for.
     */
    val modelId: String? = null,
)

/**
 * Model state across every session this gateway is watching.
 *
 * [lastKnownCatalog] exists because of a gap in the protocol: **there is no way
 * to list models without a session.** The `initialize` handshake enumerates 24
 * extension methods and none of them is a model catalog, and the list only ever
 * arrives attached to a session's config options (PROTOCOL-FINDINGS §4d). So a
 * create screen that wants to offer a model picker before the session exists can
 * only reuse a catalog seen earlier in this connection — and must cope with it
 * being empty on a cold start, when the only honest option is to create the
 * session first and switch afterwards.
 */
public data class ModelState(
    val bySession: Map<String, ModelSelection> = emptyMap(),
    val lastKnownCatalog: List<KiroModel> = emptyList(),
) {
    public fun forSession(sessionId: String): ModelSelection =
        bySession[sessionId] ?: ModelSelection.Unknown
}

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
 * Kiro's service refused the request as unauthorized.
 *
 * **The name overstates what is known and is kept only because the app already
 * catches it.** Entitlement is one cause; the credential the *bridge* itself
 * authenticates with is another, and on 2026-09-03 it was the second one — a
 * `KIRO_API_KEY`-provisioned bridge on a Pro+ account, refused for every cloud
 * call, where an interactive login on the same account worked (PROTOCOL-FINDINGS
 * §4b, correction dated 2026-09-03). Read [Throwable.message], which names both;
 * do not add copy of your own that asserts the plan is at fault.
 *
 * It has to be derived from a failed call rather than probed in advance: F-01
 * established `whoami --format json` returns identity only, with no plan or
 * entitlement field.
 */
public class NotEntitledException(message: String) : Exception(message)

/** The preview caps concurrent cloud sessions at 10. Worth its own message. */
public class SessionLimitReachedException(message: String) : Exception(message)
