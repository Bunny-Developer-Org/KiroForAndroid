package dev.kiro.core.session

import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ExecutionTarget
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.PermissionOption
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SessionStatus
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.model.SourceRepo
import dev.kiro.core.model.ToolCall
import dev.kiro.core.model.UserInputRequest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An in-memory gateway with no bridge behind it.
 *
 * This is not a test double that happens to be shipped — it is what makes the
 * parallelisation in FEATURES work. Every UI item (F-10 through F-14) can be
 * built, previewed and tested against this while F-03 and F-15 are still being
 * written, and it lives in `main` rather than `test` precisely so Compose
 * previews can use it.
 *
 * It streams a scripted turn slowly enough to exercise the coalescing the
 * transcript is required to do.
 */
public class FakeGateway(
    private val sessions: MutableList<CloudSession> = defaultSessions.toMutableList(),
) : CloudSessionGateway {

    private val _connection = MutableStateFlow<ConnectionState>(
        ConnectionState.Connected(agentSupportsCloudSessions = true, supportsImages = true),
    )
    override val connection: Flow<ConnectionState> = _connection.asStateFlow()

    private val _updates = MutableSharedFlow<SessionUpdate>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val updates: Flow<SessionUpdate> = _updates.asSharedFlow()

    private val _permissions = MutableSharedFlow<PermissionRequest>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val permissionRequests: Flow<PermissionRequest> = _permissions.asSharedFlow()

    private val _userInput = MutableSharedFlow<UserInputRequest>(replay = 1)
    override val userInputRequests: Flow<UserInputRequest> = _userInput.asSharedFlow()

    private val _roster = MutableSharedFlow<RosterChange>(extraBufferCapacity = 8)
    override val rosterChanges: Flow<RosterChange> = _roster.asSharedFlow()

    /** Flip to exercise the degradation contract without unplugging anything. */
    public fun simulateUnreachable(lastSeenMillis: Long?, workstationOnly: Boolean = true) {
        _connection.value = ConnectionState.Unreachable(lastSeenMillis, workstationOnly)
    }

    override suspend fun listSessions(source: SessionSource, scope: ListScope): List<CloudSession> =
        sessions.filter { source == SessionSource.ALL || it.source == source }

    override suspend fun createSession(request: CreateSessionRequest): CloudSession {
        val created = CloudSession(
            id = "fake-${sessions.size + 1}",
            title = request.firstPrompt.take(40),
            source = SessionSource.REMOTE,
            executionTarget = ExecutionTarget.CLOUD_SANDBOX,
            status = SessionStatus.IN_PROGRESS,
            instanceStatus = InstanceStatus.RUNNING,
            repositories = request.repositories.map { SourceRepo("GITHUB", it, null) },
            agentMode = request.modeId ?: "vibe",
            createdAt = null,
            updatedAt = null,
            cwd = "",
        )
        sessions.add(0, created)
        _roster.emit(RosterChange(listOf(created), emptyList()))
        return created
    }

    override suspend fun loadSession(sessionId: String, source: SessionSource) {
        replayScriptedTurn(sessionId)
    }

    override suspend fun prompt(sessionId: String, blocks: List<PromptBlock>): String {
        val text = blocks.filterIsInstance<PromptBlock.Text>().joinToString(" ") { it.text }
        _updates.emit(SessionUpdate.UserMessageChunk(sessionId, text))
        replayScriptedTurn(sessionId)
        return "end_turn"
    }

    /**
     * Emits one chunk at a time with a real delay, so the transcript's coalescing
     * tick is exercised rather than bypassed by an instant burst.
     */
    private suspend fun replayScriptedTurn(sessionId: String) {
        _updates.emit(SessionUpdate.TurnStarted(sessionId))
        val toolCall = ToolCall(
            toolCallId = "fake-tool-1",
            title = "ls -la",
            kind = "execute",
            status = ToolCall.Status.PENDING,
            rawInput = mapOf("command" to "ls -la"),
            output = emptyList(),
        )
        _updates.emit(SessionUpdate.ToolCallStarted(sessionId, toolCall))
        _permissions.emit(
            PermissionRequest(
                sessionId = sessionId,
                toolCallId = toolCall.toolCallId,
                title = "ls -la",
                options = listOf(
                    PermissionOption("accept", "Allow", PermissionOption.Kind.ALLOW_ONCE),
                    PermissionOption("always-accept", "Always allow", PermissionOption.Kind.ALLOW_ALWAYS),
                    PermissionOption("reject", "Deny", PermissionOption.Kind.REJECT_ONCE),
                ),
                consent = PermissionRequest.Consent("shell", "ls -la", "implicit", null),
                rpcId = null,
            ),
        )
        for (chunk in SCRIPTED_REPLY) {
            delay(CHUNK_INTERVAL_MILLIS)
            _updates.emit(SessionUpdate.AgentMessageChunk(sessionId, chunk, "fake-reply"))
        }
        _updates.emit(SessionUpdate.TurnEnded(sessionId, "end_turn"))
        _updates.emit(
            SessionUpdate.TurnCompleted(sessionId, 0.134, "credit", 6549, listOf("execute_bash")),
        )
    }

    override suspend fun cancel(sessionId: String) {
        _updates.emit(SessionUpdate.TurnEnded(sessionId, "cancelled"))
    }

    override suspend fun setMode(sessionId: String, modeId: String): Unit = Unit

    override suspend fun setModel(sessionId: String, modelId: String): Unit = Unit

    override suspend fun respondToPermission(
        sessionId: String,
        toolCallId: String,
        optionId: String,
    ) {
        _updates.emit(
            SessionUpdate.InteractionResolved(sessionId, toolCallId, "selected", optionId),
        )
    }

    override suspend fun respondToUserInput(sessionId: String, toolCallId: String, answer: String?): Unit = Unit

    override suspend fun deleteSession(sessionId: String) {
        sessions.removeAll { it.id == sessionId }
        _roster.emit(RosterChange(emptyList(), listOf(sessionId)))
    }

    override suspend fun listSourceProviders(): List<SourceProvider> = listOf(
        SourceProvider("GITHUB", "GitHub", SourceProvider.ConnectionStatus.CONNECTED),
        SourceProvider("GITLAB", "GitLab", SourceProvider.ConnectionStatus.NOT_CONNECTED),
    )

    override suspend fun listRepositories(providerType: String): List<RepoCandidate> = listOf(
        RepoCandidate(providerType, "example-org/kiro-android", null, "private", "main"),
        RepoCandidate(providerType, "example-org/docs", null, "public", "master"),
    )

    override suspend fun disconnect() {
        _connection.value = ConnectionState.Disconnected
    }

    private companion object {
        const val CHUNK_INTERVAL_MILLIS = 40L

        val SCRIPTED_REPLY = listOf(
            "I looked at ", "the repository ", "and the build ", "is green. ",
            "The failing test ", "was a stale fixture; ", "I have opened a PR.",
        )

        val defaultSessions = listOf(
            CloudSession(
                id = "61da09f4-85be-401f-9d0e-ea71c9195f7b",
                title = "Fix flaky integration test",
                source = SessionSource.REMOTE,
                executionTarget = ExecutionTarget.CLOUD_SANDBOX,
                status = SessionStatus.IDLE,
                instanceStatus = InstanceStatus.SUSPENDED,
                repositories = listOf(SourceRepo("GITHUB", "example-org/repo-1", null)),
                agentMode = "vibe",
                createdAt = "2026-09-01T19:48:07.445Z",
                updatedAt = "2026-09-01T20:19:43.626Z",
                cwd = "",
            ),
            CloudSession(
                id = "3b89a4f3-4065-43a7-a1ff-3d67b0e8100a",
                title = "Add pagination to the API",
                source = SessionSource.REMOTE,
                executionTarget = ExecutionTarget.CLOUD_SANDBOX,
                status = SessionStatus.IN_PROGRESS,
                instanceStatus = InstanceStatus.RUNNING,
                repositories = listOf(SourceRepo("GITHUB", "example-org/repo-2", null)),
                agentMode = "autonomous",
                createdAt = "2026-08-31T21:20:27.227Z",
                updatedAt = "2026-08-31T21:20:42.474Z",
                cwd = "",
            ),
        )
    }
}
