package dev.kiro.android.ui.create

import app.cash.turbine.test
import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ExecutionTarget
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.RepoSlug
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SessionStatus
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.model.SourceRepo
import dev.kiro.core.model.UserInputRequest
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.ConnectionState
import dev.kiro.core.session.CreateSessionRequest
import dev.kiro.core.session.LayerState
import dev.kiro.core.session.NotEntitledException
import dev.kiro.core.session.PromptBlock
import dev.kiro.core.session.RepoCatalog
import dev.kiro.core.session.RepoSuggestion
import dev.kiro.core.session.RosterChange
import dev.kiro.core.session.SessionLimitReachedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A minimal [CloudSessionGateway]: only what [CreateSessionViewModel] calls
 * does anything, mirroring the pattern in
 * `dev.kiro.android.ui.sessions.SessionListViewModelTest`.
 */
private class TestGateway(
    private val onCreate: suspend (CreateSessionRequest) -> CloudSession = { request ->
        fakeSession("created", request.repositories)
    },
) : CloudSessionGateway {
    override val connection: Flow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)
    override val updates: Flow<SessionUpdate> = MutableSharedFlow()
    override val permissionRequests: Flow<PermissionRequest> = MutableSharedFlow()
    override val userInputRequests: Flow<UserInputRequest> = MutableSharedFlow()
    override val rosterChanges: Flow<RosterChange> = MutableSharedFlow()

    var lastPrompt: Pair<String, List<PromptBlock>>? = null

    override suspend fun listSessions(source: SessionSource, scope: ListScope): List<CloudSession> = emptyList()
    override suspend fun createSession(request: CreateSessionRequest): CloudSession = onCreate(request)
    override suspend fun loadSession(sessionId: String, source: SessionSource) = Unit
    override suspend fun prompt(sessionId: String, blocks: List<PromptBlock>): String? {
        lastPrompt = sessionId to blocks
        return null
    }
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun setMode(sessionId: String, modeId: String) = Unit
    override suspend fun setModel(sessionId: String, modelId: String) = Unit
    override suspend fun respondToPermission(sessionId: String, toolCallId: String, optionId: String) = Unit
    override suspend fun respondToUserInput(sessionId: String, toolCallId: String, answer: String?) = Unit
    override suspend fun deleteSession(sessionId: String) = Unit
    override suspend fun listSourceProviders(): List<SourceProvider> = emptyList()
    override suspend fun listRepositories(providerType: String): List<RepoCandidate> = emptyList()
    override suspend fun disconnect() = Unit
}

/** A [RepoCatalog] whose [catalog] result is queued, so a retry can be scripted to recover. */
private class TestRepoCatalog(
    private val catalogResults: List<Result<List<RepoSuggestion>>> = listOf(Result.success(emptyList())),
    private val providersResult: Result<List<SourceProvider>> = Result.success(emptyList()),
    private val recentResult: List<RepoSuggestion> = emptyList(),
) : RepoCatalog {
    private var catalogCallIndex = 0
    val noteUsedCalls = mutableListOf<Pair<List<String>, Map<String, String?>>>()

    override suspend fun providers(): List<SourceProvider> = providersResult.getOrThrow()

    override suspend fun catalog(): List<RepoSuggestion> {
        val index = catalogCallIndex.coerceAtMost(catalogResults.lastIndex)
        catalogCallIndex++
        return catalogResults[index].getOrThrow()
    }

    override suspend fun recent(): List<RepoSuggestion> = recentResult

    override fun parse(input: String): Result<RepoSuggestion> =
        RepoSlug.parse(input).map { RepoSuggestion(it, null, null, false, RepoSuggestion.Origin.MANUAL) }

    override suspend fun noteUsed(slugs: List<String>, providerTypes: Map<String, String?>) {
        noteUsedCalls += slugs to providerTypes
    }
}

private fun fakeSession(id: String, repos: List<String>) = CloudSession(
    id = id,
    title = "title-$id",
    source = SessionSource.REMOTE,
    executionTarget = ExecutionTarget.CLOUD_SANDBOX,
    status = SessionStatus.IN_PROGRESS,
    instanceStatus = InstanceStatus.RUNNING,
    repositories = repos.map { SourceRepo("GITHUB", it, null) },
    agentMode = "vibe",
    createdAt = null,
    updatedAt = null,
    cwd = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
class CreateSessionViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initialRepos seeds selected immediately as manual entries`() = runTest {
        val viewModel = CreateSessionViewModel(TestGateway(), TestRepoCatalog(), initialRepos = listOf("a/b"))

        val selected = viewModel.state.value.selected
        assertEquals(listOf("a/b"), selected.map { it.slug })
        assertTrue(selected.all { it.origin == RepoSuggestion.Origin.MANUAL })
    }

    @Test
    fun `manual add appears in selected and is removable`() = runTest {
        val viewModel = CreateSessionViewModel(TestGateway(), TestRepoCatalog())

        viewModel.setManualEntry("owner/repo")
        viewModel.addManual()
        assertEquals(listOf("owner/repo"), viewModel.state.value.selected.map { it.slug })
        assertNull(viewModel.state.value.manualError)

        viewModel.remove("owner/repo")
        assertEquals(emptyList(), viewModel.state.value.selected)
    }

    @Test
    fun `invalid manual entry sets manualError without changing selected`() = runTest {
        val viewModel = CreateSessionViewModel(TestGateway(), TestRepoCatalog())

        viewModel.setManualEntry("not-a-slug")
        viewModel.addManual()

        assertNotNull(viewModel.state.value.manualError)
        assertEquals(emptyList(), viewModel.state.value.selected)
    }

    @Test
    fun `a failing catalog yields Failed rather than an empty Ready`() = runTest {
        val catalog = TestRepoCatalog(
            catalogResults = listOf(Result.failure(RuntimeException("listSourceProviders failed"))),
        )
        val viewModel = CreateSessionViewModel(TestGateway(), catalog)

        viewModel.state.test {
            var last = awaitItem()
            while (last.catalog is LayerState.Loading) last = awaitItem()
            assertTrue(last.catalog is LayerState.Failed)
            assertEquals("listSourceProviders failed", (last.catalog as LayerState.Failed).reason)
        }
    }

    @Test
    fun `retryCatalog recovers from a prior failure`() = runTest {
        val catalog = TestRepoCatalog(
            catalogResults = listOf(
                Result.failure(RuntimeException("boom")),
                Result.success(
                    listOf(RepoSuggestion("owner/repo", "GITHUB", "main", false, RepoSuggestion.Origin.CATALOG)),
                ),
            ),
        )
        val viewModel = CreateSessionViewModel(TestGateway(), catalog)

        viewModel.state.test {
            var last = awaitItem()
            while (last.catalog is LayerState.Loading) last = awaitItem()
            assertTrue(last.catalog is LayerState.Failed)

            viewModel.retryCatalog()

            last = awaitItem()
            while (last.catalog is LayerState.Loading) last = awaitItem()
            assertTrue(last.catalog is LayerState.Ready)
            assertEquals(
                listOf("owner/repo"),
                (last.catalog as LayerState.Ready<List<RepoSuggestion>>).value.map { it.slug },
            )
        }
    }

    @Test
    fun `create maps NotEntitledException to its message`() = runTest {
        val gateway = TestGateway(onCreate = { throw NotEntitledException("Upgrade to Pro.") })
        val viewModel = CreateSessionViewModel(gateway, TestRepoCatalog(), initialRepos = listOf("owner/repo"))

        var created: CloudSession? = null
        viewModel.create("do it", null) { created = it }

        assertNull(created)
        assertEquals("Upgrade to Pro.", viewModel.state.value.error)
        assertEquals(false, viewModel.state.value.busy)
    }

    @Test
    fun `create maps SessionLimitReachedException to its message`() = runTest {
        val gateway = TestGateway(onCreate = { throw SessionLimitReachedException("Too many sessions.") })
        val viewModel = CreateSessionViewModel(gateway, TestRepoCatalog(), initialRepos = listOf("owner/repo"))

        viewModel.create("do it", null) {}

        assertEquals("Too many sessions.", viewModel.state.value.error)
    }

    @Test
    fun `create wraps any other failure with a generic prefix`() = runTest {
        val gateway = TestGateway(onCreate = { throw IllegalStateException("socket closed") })
        val viewModel = CreateSessionViewModel(gateway, TestRepoCatalog(), initialRepos = listOf("owner/repo"))

        viewModel.create("do it", null) {}

        assertEquals("Could not start the session: socket closed", viewModel.state.value.error)
    }

    @Test
    fun `create notes the repositories as used and forwards the created session`() = runTest {
        val gateway = TestGateway()
        val catalog = TestRepoCatalog()
        val viewModel = CreateSessionViewModel(gateway, catalog, initialRepos = listOf("owner/repo"))

        var created: CloudSession? = null
        viewModel.create("do it", "vibe") { created = it }

        assertEquals("created", created?.id)
        val expectedNoteUsed = listOf(listOf("owner/repo") to mapOf<String, String?>("owner/repo" to null))
        assertEquals(expectedNoteUsed, catalog.noteUsedCalls)
        assertNull(viewModel.state.value.error)
        assertEquals(false, viewModel.state.value.busy)
    }
}
