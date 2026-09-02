package dev.kiro.android.ui.transcript

import androidx.lifecycle.ViewModelStore
import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SourceProvider
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the userInput half of [TranscriptViewModel] -- the approval half
 * ([TranscriptViewModel.permission]) predates this file and is exercised
 * manually via [dev.kiro.core.session.FakeGateway]'s scripted turn.
 *
 * [TranscriptViewModel] runs an unrelated infinite `while (isActive) {
 * delay(TICK_MILLIS); drain() }` coalescing loop in its `viewModelScope`. Two
 * things follow from that:
 *
 *  - Tests never call `advanceUntilIdle()` -- it would try to run that loop
 *    forever. [runCurrent] only drains tasks already due at the current
 *    virtual time, enough for the `userInputRequests`/`updates` collectors to
 *    react to an emission without touching the loop's future-scheduled delay.
 *  - Every `TranscriptViewModel` is created through a [ViewModelStore] and the
 *    store is cleared at the end of the test. `runTest` drains its scheduler
 *    to idle as part of tearing itself down, and a real Android `ViewModel`
 *    never sees `onCleared()` unless something calls `store.clear()` --
 *    without it the loop is still alive when that drain runs and it spins
 *    forever (confirmed via `jstack`: stuck in
 *    `TestCoroutineScheduler.tryRunNextTaskUnless`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Creates a [TranscriptViewModel] owned by a throwaway [ViewModelStore], runs
     * [block], then clears the store -- cancelling `viewModelScope` -- before
     * `runTest` returns.
     */
    private fun viewModelTest(
        sessionId: String = "s1",
        block: suspend TestScope.(TranscriptViewModel, RecordingGateway) -> Unit,
    ) = runTest(testDispatcher) {
        val gateway = RecordingGateway()
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
    fun `userInput request for this session is surfaced`() = viewModelTest { viewModel, gateway ->
        runCurrent() // let init{}'s collectors subscribe

        val request = UserInputRequest("s1", "tool-1", "What should the branch be called?", "e.g. fix-flaky-test")
        gateway.userInput.emit(request)
        runCurrent() // let the collector react to the emission

        assertEquals(request, viewModel.userInput.value)
    }

    @Test
    fun `userInput for a different session is ignored`() = viewModelTest { viewModel, gateway ->
        runCurrent()

        gateway.userInput.emit(UserInputRequest("other-session", "tool-1", "Q?", null))
        runCurrent()

        assertNull(viewModel.userInput.value)
    }

    @Test
    fun `respondToUserInput clears state and forwards the answer`() = viewModelTest { viewModel, gateway ->
        runCurrent()
        gateway.userInput.emit(UserInputRequest("s1", "tool-1", "Q?", null))
        runCurrent()
        assertEquals("tool-1", viewModel.userInput.value?.toolCallId)

        viewModel.respondToUserInput("a real answer")
        // Cleared synchronously, before the round trip to the gateway resolves.
        assertNull(viewModel.userInput.value)
        runCurrent()

        val expected: List<Triple<String, String, String?>> = listOf(Triple("s1", "tool-1", "a real answer"))
        assertEquals(expected, gateway.userInputResponses)
    }

    @Test
    fun `dismissing sends a null answer rather than an empty string`() = viewModelTest { viewModel, gateway ->
        runCurrent()
        gateway.userInput.emit(UserInputRequest("s1", "tool-1", "Q?", null))
        runCurrent()

        viewModel.respondToUserInput(null)
        runCurrent()

        val expected: List<Triple<String, String, String?>> = listOf(Triple("s1", "tool-1", null))
        assertEquals(expected, gateway.userInputResponses)
    }

    @Test
    fun `respondToUserInput is a no-op when nothing is pending`() = viewModelTest { viewModel, gateway ->
        runCurrent()

        viewModel.respondToUserInput("too late")
        runCurrent()

        assertNull(viewModel.userInput.value)
        assertEquals(emptyList<Triple<String, String, String?>>(), gateway.userInputResponses)
    }

    @Test
    fun `a resolution from another client clears the pending question`() = viewModelTest { viewModel, gateway ->
        runCurrent()
        gateway.userInput.emit(UserInputRequest("s1", "tool-1", "Q?", null))
        runCurrent()
        assertEquals("tool-1", viewModel.userInput.value?.toolCallId)

        // Answered from another device on the same account -- the stream, not a
        // response to a call this instance made, is what has to clear the card.
        // It only takes effect on the next coalescing tick (see the class doc),
        // so advance exactly one tick -- bounded, unlike advanceUntilIdle().
        gateway.updates.emit(SessionUpdate.InteractionResolved("s1", "tool-1", "answered", "someone else's answer"))
        advanceTimeBy(TranscriptViewModel.TICK_MILLIS + 1)
        runCurrent()

        assertNull(viewModel.userInput.value)
    }

    @Test
    fun `send with text only builds a single Text block`() = viewModelTest { viewModel, gateway ->
        runCurrent()

        viewModel.send("hello there")
        runCurrent()

        assertEquals(
            listOf("s1" to listOf<PromptBlock>(PromptBlock.Text("hello there"))),
            gateway.promptCalls,
        )
    }

    @Test
    fun `send with text and image builds both blocks`() = viewModelTest { viewModel, gateway ->
        runCurrent()

        val image = PromptBlock.Image("image/png", "base64data")
        viewModel.send("look at this", image)
        runCurrent()

        assertEquals(
            listOf("s1" to listOf<PromptBlock>(PromptBlock.Text("look at this"), image)),
            gateway.promptCalls,
        )
    }

    @Test
    fun `send with only an image omits the Text block`() = viewModelTest { viewModel, gateway ->
        runCurrent()

        val image = PromptBlock.Image("image/jpeg", "morebase64")
        viewModel.send("", image)
        runCurrent()

        assertEquals(
            listOf("s1" to listOf<PromptBlock>(image)),
            gateway.promptCalls,
        )
    }

    @Test
    fun `send with blank text and no image is a no-op`() = viewModelTest { viewModel, gateway ->
        runCurrent()

        viewModel.send("   ")
        runCurrent()

        assertEquals(emptyList<Pair<String, List<PromptBlock>>>(), gateway.promptCalls)
    }

    /**
     * A minimal [CloudSessionGateway] double, purpose-built to record
     * `respondToUserInput` calls precisely rather than infer them from
     * [dev.kiro.core.session.FakeGateway]'s scripted-turn side effects.
     */
    private class RecordingGateway : CloudSessionGateway {
        val userInputResponses = mutableListOf<Triple<String, String, String?>>()

        /** Every call to [prompt], recorded as (sessionId, blocks) for assertions. */
        val promptCalls = mutableListOf<Pair<String, List<PromptBlock>>>()

        override val connection: Flow<ConnectionState> = MutableSharedFlow()
        override val updates: MutableSharedFlow<SessionUpdate> = MutableSharedFlow(extraBufferCapacity = 16)
        override val permissionRequests: Flow<PermissionRequest> = MutableSharedFlow()
        override val userInputRequests: MutableSharedFlow<UserInputRequest> =
            MutableSharedFlow(extraBufferCapacity = 16)
        override val rosterChanges: Flow<RosterChange> = MutableSharedFlow()

        /** Test-facing alias so call sites read `gateway.userInput.emit(...)`. */
        val userInput: MutableSharedFlow<UserInputRequest> get() = userInputRequests

        override suspend fun listSessions(source: SessionSource, scope: ListScope): List<CloudSession> = emptyList()
        override suspend fun createSession(request: CreateSessionRequest): CloudSession =
            error("not used by this test")
        override suspend fun loadSession(sessionId: String, source: SessionSource): Unit = Unit
        override suspend fun prompt(sessionId: String, blocks: List<PromptBlock>): String? {
            promptCalls += sessionId to blocks
            return null
        }
        override suspend fun cancel(sessionId: String): Unit = Unit
        override suspend fun setMode(sessionId: String, modeId: String): Unit = Unit
        override suspend fun setModel(sessionId: String, modelId: String): Unit = Unit
        override suspend fun respondToPermission(sessionId: String, toolCallId: String, optionId: String): Unit = Unit
        override suspend fun respondToUserInput(sessionId: String, toolCallId: String, answer: String?) {
            userInputResponses += Triple(sessionId, toolCallId, answer)
        }
        override suspend fun deleteSession(sessionId: String): Unit = Unit
        override suspend fun listSourceProviders(): List<SourceProvider> = emptyList()
        override suspend fun listRepositories(providerType: String): List<RepoCandidate> = emptyList()
        override suspend fun disconnect(): Unit = Unit
    }
}
