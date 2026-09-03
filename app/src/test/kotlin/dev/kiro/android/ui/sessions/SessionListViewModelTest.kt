package dev.kiro.android.ui.sessions

import app.cash.turbine.test
import dev.kiro.android.platform.PinnedSessionStore
import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ExecutionTarget
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SessionStatus
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.model.SourceRepo
import dev.kiro.core.model.UserInputRequest
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.ConnectionState
import dev.kiro.core.session.CreateSessionRequest
import dev.kiro.core.session.PromptBlock
import dev.kiro.core.session.RosterChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the two behaviours [SessionListViewModel] adds over the old
 * `remember {}` state in `AppNavigation`: optimistic, confirmed delete, and a
 * locally-persisted pin that sorts to the top of the roster without disturbing
 * the rest of the order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun session(id: String) = CloudSession(
        id = id,
        title = "title-$id",
        source = SessionSource.REMOTE,
        executionTarget = ExecutionTarget.CLOUD_SANDBOX,
        status = SessionStatus.IDLE,
        instanceStatus = InstanceStatus.RUNNING,
        repositories = listOf(SourceRepo("GITHUB", "example-org/$id", null)),
        agentMode = "vibe",
        createdAt = null,
        updatedAt = null,
        cwd = "",
    )

    /** A minimal [CloudSessionGateway]: only what [SessionListViewModel] calls does anything. */
    private class TestGateway(sessions: List<CloudSession>) : CloudSessionGateway {
        override val connection: Flow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
        override val updates: Flow<SessionUpdate> = MutableSharedFlow()
        override val permissionRequests: Flow<PermissionRequest> = MutableSharedFlow()
        override val userInputRequests: Flow<UserInputRequest> = MutableSharedFlow()

        private val _roster = MutableSharedFlow<RosterChange>(extraBufferCapacity = 8)
        override val rosterChanges: Flow<RosterChange> = _roster.asSharedFlow()

        private val listed = sessions
        val deletedIds = mutableListOf<String>()

        override suspend fun listSessions(source: SessionSource, scope: ListScope): List<CloudSession> = listed
        override suspend fun createSession(request: CreateSessionRequest): CloudSession = error("unused in this test")
        override suspend fun loadSession(sessionId: String, source: SessionSource) = Unit
        override suspend fun prompt(sessionId: String, blocks: List<PromptBlock>): String? = null
        override suspend fun cancel(sessionId: String) = Unit
        override suspend fun setMode(sessionId: String, modeId: String) = Unit
        override suspend fun setModel(sessionId: String, modelId: String) = Unit
        override suspend fun respondToPermission(sessionId: String, toolCallId: String, optionId: String) = Unit
        override suspend fun respondToUserInput(sessionId: String, toolCallId: String, answer: String?) = Unit
        override suspend fun deleteSession(sessionId: String) {
            deletedIds += sessionId
        }
        override suspend fun listSourceProviders(): List<SourceProvider> = emptyList()
        override suspend fun listRepositories(providerType: String): List<RepoCandidate> = emptyList()
        override suspend fun disconnect() = Unit

        suspend fun pushRoster(change: RosterChange) = _roster.emit(change)
    }

    private class TestPinnedStore : PinnedSessionStore {
        private val _pinnedIds = MutableStateFlow<Set<String>>(emptySet())
        override val pinnedIds: Flow<Set<String>> = _pinnedIds.asStateFlow()
        override suspend fun toggle(sessionId: String) {
            _pinnedIds.value = _pinnedIds.value.let { if (sessionId in it) it - sessionId else it + sessionId }
        }
    }

    @Test
    fun `loads sessions from the gateway on init`() = runTest {
        val gateway = TestGateway(listOf(session("a"), session("b")))
        val viewModel = SessionListViewModel(gateway, TestPinnedStore())

        viewModel.sessions.test {
            assertEquals(listOf("a", "b"), awaitItem().map { it.id })
        }
    }

    @Test
    fun `roster push upserts and removes sessions without a reload`() = runTest {
        val gateway = TestGateway(listOf(session("a")))
        val viewModel = SessionListViewModel(gateway, TestPinnedStore())

        viewModel.sessions.test {
            assertEquals(listOf("a"), awaitItem().map { it.id })

            gateway.pushRoster(RosterChange(upserted = listOf(session("b")), deleted = emptyList()))
            assertEquals(listOf("b", "a"), awaitItem().map { it.id })

            gateway.pushRoster(RosterChange(upserted = emptyList(), deleted = listOf("a")))
            assertEquals(listOf("b"), awaitItem().map { it.id })
        }
    }

    @Test
    fun `delete clears the session immediately and forwards to the gateway`() = runTest {
        val gateway = TestGateway(listOf(session("a"), session("b")))
        val viewModel = SessionListViewModel(gateway, TestPinnedStore())

        viewModel.sessions.test {
            assertEquals(listOf("a", "b"), awaitItem().map { it.id })

            viewModel.delete("a")
            assertEquals(listOf("b"), awaitItem().map { it.id })
        }

        assertEquals(listOf("a"), gateway.deletedIds)
    }

    @Test
    fun `pinning a session moves it to the top without reordering the rest`() = runTest {
        val gateway = TestGateway(listOf(session("a"), session("b"), session("c")))
        val viewModel = SessionListViewModel(gateway, TestPinnedStore())

        viewModel.sessions.test {
            assertEquals(listOf("a", "b", "c"), awaitItem().map { it.id })

            viewModel.togglePin("b")
            assertEquals(listOf("b", "a", "c"), awaitItem().map { it.id })

            viewModel.togglePin("b")
            assertEquals(listOf("a", "b", "c"), awaitItem().map { it.id })
        }
    }
}
