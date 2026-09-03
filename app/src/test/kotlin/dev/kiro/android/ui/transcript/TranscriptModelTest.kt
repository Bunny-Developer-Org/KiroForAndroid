package dev.kiro.android.ui.transcript

import androidx.lifecycle.ViewModelStore
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
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.ConnectionState
import dev.kiro.core.session.CreateSessionRequest
import dev.kiro.core.session.ModelState
import dev.kiro.core.session.PromptBlock
import dev.kiro.core.session.RosterChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse

/**
 * The model half of [TranscriptViewModel].
 *
 * Shares [TranscriptViewModelTest]'s two structural rules and for the same
 * reasons: never `advanceUntilIdle()` (the coalescing loop never idles) and
 * always clear a [ViewModelStore] so `viewModelScope` is cancelled before
 * `runTest` drains its scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelTest(
        sessionId: String = "s1",
        gateway: ModelGateway = ModelGateway(),
        block: suspend TestScope.(TranscriptViewModel, ModelGateway) -> Unit,
    ) = runTest(testDispatcher) {
        val store = ViewModelStore()
        val viewModel = TranscriptViewModel(gateway, sessionId)
        store.put("under-test", viewModel)
        try {
            block(viewModel, gateway)
        } finally {
            store.clear()
        }
    }

    @Test
    fun `a session with nothing reported starts unknown rather than defaulted`() =
        viewModelTest { viewModel, _ ->
            // What a cloud session looks like between session/load returning and
            // the sandbox's first config_option_update (PROTOCOL-FINDINGS §4d).
            assertEquals(ModelSelection.Unknown, viewModel.models.value)
            assertFalse(viewModel.models.value.isKnown)
        }

    @Test
    fun `the last known selection is seeded synchronously, without waiting for the flow`() {
        val gateway = ModelGateway(ModelState(mapOf("s1" to ModelSelection(CATALOG, "claude-opus-5"))))
        viewModelTest(gateway = gateway) { viewModel, _ ->
            // Read before any coroutine runs: reopening a session must not flash
            // "not reported yet" for a frame.
            assertEquals("claude-opus-5", viewModel.models.value.currentId)
        }
    }

    @Test
    fun `a later config_option_update fills in what was unknown`() = viewModelTest { viewModel, gateway ->
        runCurrent()
        assertFalse(viewModel.models.value.isKnown)

        gateway.reports.value = ModelState(mapOf("s1" to ModelSelection(CATALOG, "gpt-5.6-luna")), CATALOG)
        runCurrent()

        assertEquals("gpt-5.6-luna", viewModel.models.value.currentId)
        assertEquals("GPT 5.6 Luna", viewModel.models.value.current?.name)
    }

    @Test
    fun `another session's model report is ignored`() = viewModelTest { viewModel, gateway ->
        runCurrent()

        gateway.reports.value = ModelState(mapOf("other" to ModelSelection(CATALOG, "claude-opus-5")), CATALOG)
        runCurrent()

        assertEquals(ModelSelection.Unknown, viewModel.models.value)
    }

    @Test
    fun `setModel forwards the request and does not paint the switch itself`() =
        viewModelTest { viewModel, gateway ->
            gateway.reports.value = ModelState(mapOf("s1" to ModelSelection(CATALOG, "auto")), CATALOG)
            runCurrent()

            viewModel.setModel("gpt-5.6-luna")
            assertIs<ModelChange.InFlight>(viewModel.modelChange.value)
            runCurrent()

            assertEquals(listOf("s1" to "gpt-5.6-luna"), gateway.setModelCalls)
            assertEquals(ModelChange.Idle, viewModel.modelChange.value)
            // The gateway has not reported anything new, so the bar still says
            // `auto`. Painting the request here would show a model the session is
            // not running.
            assertEquals("auto", viewModel.models.value.currentId)
        }

    @Test
    fun `the agent's own report is what moves the bar`() = viewModelTest { viewModel, gateway ->
        gateway.reports.value = ModelState(mapOf("s1" to ModelSelection(CATALOG, "auto")), CATALOG)
        runCurrent()

        viewModel.setModel("gpt-5.6-luna")
        runCurrent()
        gateway.reports.value = ModelState(mapOf("s1" to ModelSelection(CATALOG, "gpt-5.6-luna")), CATALOG)
        runCurrent()

        assertEquals("gpt-5.6-luna", viewModel.models.value.currentId)
    }

    @Test
    fun `a refusal is named and leaves the reported model untouched`() = viewModelTest { viewModel, gateway ->
        gateway.reports.value = ModelState(mapOf("s1" to ModelSelection(CATALOG, "auto")), CATALOG)
        gateway.setModelFailure = IllegalStateException("model not available on your plan")
        runCurrent()

        viewModel.setModel("claude-opus-5")
        runCurrent()

        val failed = assertIs<ModelChange.Failed>(viewModel.modelChange.value)
        assertEquals("claude-opus-5", failed.modelId)
        assertEquals("model not available on your plan", failed.message)
        // The session is still usable on the model it already had.
        assertEquals("auto", viewModel.models.value.currentId)
    }

    @Test
    fun `a refusal with no message still says something`() = viewModelTest { viewModel, gateway ->
        gateway.setModelFailure = IllegalStateException()
        runCurrent()

        viewModel.setModel("claude-opus-5")
        runCurrent()

        val failed = assertIs<ModelChange.Failed>(viewModel.modelChange.value)
        assertEquals(TranscriptViewModel.DEFAULT_REFUSAL, failed.message)
    }

    @Test
    fun `dismissing a refusal clears it`() = viewModelTest { viewModel, gateway ->
        gateway.setModelFailure = IllegalStateException("nope")
        runCurrent()
        viewModel.setModel("claude-opus-5")
        runCurrent()
        assertIs<ModelChange.Failed>(viewModel.modelChange.value)

        viewModel.dismissModelChangeError()

        assertEquals(ModelChange.Idle, viewModel.modelChange.value)
    }

    @Test
    fun `a second switch is refused while one is still in flight`() = viewModelTest { viewModel, gateway ->
        runCurrent()

        viewModel.setModel("gpt-5.6-luna")
        viewModel.setModel("claude-opus-5")
        runCurrent()

        assertEquals(listOf("s1" to "gpt-5.6-luna"), gateway.setModelCalls)
    }

    /** Records [setModel] exactly and lets a test push model reports at will. */
    private class ModelGateway(initial: ModelState = ModelState()) : CloudSessionGateway {
        val reports = MutableStateFlow(initial)
        val setModelCalls = mutableListOf<Pair<String, String>>()
        var setModelFailure: Throwable? = null

        override val connection: Flow<ConnectionState> = MutableSharedFlow()
        override val updates: Flow<SessionUpdate> = MutableSharedFlow()
        override val permissionRequests: Flow<PermissionRequest> = MutableSharedFlow()
        override val userInputRequests: Flow<UserInputRequest> = MutableSharedFlow()
        override val rosterChanges: Flow<RosterChange> = MutableSharedFlow()

        override val models: Flow<ModelState> get() = reports
        override fun modelsFor(sessionId: String): ModelSelection = reports.value.forSession(sessionId)

        override suspend fun setModel(sessionId: String, modelId: String) {
            setModelCalls += sessionId to modelId
            setModelFailure?.let { throw it }
        }

        override suspend fun listSessions(source: SessionSource, scope: ListScope): List<CloudSession> = emptyList()
        override suspend fun createSession(request: CreateSessionRequest): CloudSession =
            error("not used by this test")
        override suspend fun loadSession(sessionId: String, source: SessionSource): Unit = Unit
        override suspend fun prompt(sessionId: String, blocks: List<PromptBlock>): String? = null
        override suspend fun cancel(sessionId: String): Unit = Unit
        override suspend fun setMode(sessionId: String, modeId: String): Unit = Unit
        override suspend fun respondToPermission(sessionId: String, toolCallId: String, optionId: String): Unit = Unit
        override suspend fun respondToUserInput(sessionId: String, toolCallId: String, answer: String?): Unit = Unit
        override suspend fun deleteSession(sessionId: String): Unit = Unit
        override suspend fun listSourceProviders(): List<SourceProvider> = emptyList()
        override suspend fun listRepositories(providerType: String): List<RepoCandidate> = emptyList()
        override suspend fun disconnect(): Unit = Unit
    }

    private companion object {
        val CATALOG = listOf(
            KiroModel("auto", "Auto", "Models chosen by task", 1.0, "Credit"),
            KiroModel("claude-opus-5", "Claude Opus 5", "1M context window", 2.2, "Credit"),
            KiroModel("gpt-5.6-luna", "GPT 5.6 Luna", "Experimental preview", 0.1, "Credit"),
        )
    }
}
