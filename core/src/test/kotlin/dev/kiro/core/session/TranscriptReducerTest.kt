package dev.kiro.core.session

import dev.kiro.core.Fixtures
import dev.kiro.core.acp.RpcNotification
import dev.kiro.core.acp.RpcRequest
import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.acp.SessionUpdateParser
import dev.kiro.core.model.PermissionOption
import dev.kiro.core.model.ToolCall
import dev.kiro.core.model.TranscriptEntry
import dev.kiro.core.util.DriftMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptReducerTest {

    private val reducer = TranscriptReducer()

    private fun updatesFrom(fixture: String): List<SessionUpdate> =
        Fixtures.load(fixture)
            .filter { it.isFromAgent }
            .mapNotNull { it.decoded as? RpcNotification }
            .filter { it.method == "session/update" }
            .mapNotNull { SessionUpdateParser.parse(it.params) }

    /**
     * The whole captured turn, replayed. This is the test that would have caught
     * every mis-parse in the published protocol documentation.
     */
    @Test
    fun `replays a real turn into a coherent transcript`() {
        val updates = updatesFrom("prompt-turn-with-permission.jsonl")
        assertTrue(updates.isNotEmpty())

        val state = reducer.reduceAll(TranscriptReducer.State(), updates)

        // turn_end arrived, so no turn is active and the stop reason survived.
        assertFalse(state.isTurnActive)
        assertEquals("end_turn", state.stopReason)

        // The streamed message was flushed into the list exactly once.
        assertEquals("", state.streamingText)
        val agentMessages = state.entries.filterIsInstance<TranscriptEntry.AgentMessage>()
        assertEquals(1, agentMessages.size)
        assertTrue(agentMessages.single().text.isNotBlank())

        // The tool call was updated in place rather than duplicated.
        val toolCalls = state.entries.filterIsInstance<TranscriptEntry.ToolCallEntry>()
        assertEquals(1, toolCalls.size)
        assertEquals(ToolCall.Status.FAILED, toolCalls.single().toolCall.status)
        assertEquals("id -un", toolCalls.single().toolCall.rawInput["command"])

        // The approval was raised and then resolved -- by the probe's auto-reject,
        // which is the same code path as another client answering first.
        assertNull(state.pendingApproval)

        // The in-band MCP error became a visible entry, not a silent drop.
        assertTrue(state.entries.any { it is TranscriptEntry.Error })

        // Per-turn cost arrived and is renderable (F-19b).
        val summary = state.entries.filterIsInstance<TranscriptEntry.TurnSummary>().single()
        assertNotNull(summary.credits)
        assertEquals(listOf("execute_bash"), summary.usedTools)
        assertEquals(6549L, summary.elapsedMillis)

        // Context usage tracked without becoming a transcript row.
        assertNotNull(state.contextUsage)
    }

    @Test
    fun `pending interaction is surfaced before its resolution arrives`() {
        val updates = updatesFrom("prompt-turn-with-permission.jsonl")
        val pendingIndex = updates.indexOfFirst { it is SessionUpdate.PendingInteraction }
        assertTrue(pendingIndex >= 0, "fixture should contain a pending_interaction")

        val state = reducer.reduceAll(TranscriptReducer.State(), updates.take(pendingIndex + 1))
        val approval = assertNotNull(state.pendingApproval)
        assertEquals("id -un", approval.question)

        // Options are rendered as sent, never hard-coded.
        assertEquals(4, approval.options.size)
        assertTrue(approval.options.any { it.kind == PermissionOption.Kind.ALLOW_ONCE })
        assertTrue(approval.options.any { it.kind == PermissionOption.Kind.REJECT_ALWAYS })
    }

    @Test
    fun `a resolution for a different tool call does not clear the current approval`() {
        val base = TranscriptReducer.State(
            pendingApproval = TranscriptReducer.PendingApproval("tool-a", "rm -rf /", emptyList()),
        )
        val state = reducer.reduce(
            base,
            SessionUpdate.InteractionResolved("s", "tool-b", "selected", "accept"),
        )
        assertNotNull(state.pendingApproval, "a stale resolution must not silently dismiss an approval")
    }

    /**
     * A tool call arriving mid-message must not be rendered above the text that
     * preceded it — the interleaving case ACP-INTEGRATION §9 asks for.
     */
    @Test
    fun `a tool call mid-stream flushes the text before it`() {
        val state = reducer.reduceAll(
            TranscriptReducer.State(),
            listOf(
                SessionUpdate.AgentMessageChunk("s", "Let me check ", null),
                SessionUpdate.AgentMessageChunk("s", "the tests.", null),
                SessionUpdate.ToolCallStarted(
                    "s",
                    ToolCall("t1", "pytest", "execute", ToolCall.Status.PENDING, emptyMap(), emptyList()),
                ),
                SessionUpdate.AgentMessageChunk("s", "They pass.", null),
                SessionUpdate.TurnEnded("s", "end_turn"),
            ),
        )

        val kinds = state.entries.map { it::class.simpleName }
        assertEquals(listOf("AgentMessage", "ToolCallEntry", "AgentMessage"), kinds)
        assertEquals(
            "Let me check the tests.",
            (state.entries[0] as TranscriptEntry.AgentMessage).text,
        )
    }

    /**
     * A socket dropping mid-turn means a result can arrive for a call whose start
     * we never saw. Losing it would leave the user staring at a tool block that
     * never finishes; F-15 depends on this being handled here.
     */
    @Test
    fun `a result for an unseen tool call is synthesised rather than dropped`() {
        val state = reducer.reduce(
            TranscriptReducer.State(),
            SessionUpdate.ToolCallUpdated("s", "orphan", ToolCall.Status.COMPLETED, "ls", listOf("out")),
        )
        val entry = state.entries.filterIsInstance<TranscriptEntry.ToolCallEntry>().single()
        assertEquals("orphan", entry.toolCall.toolCallId)
        assertEquals(ToolCall.Status.COMPLETED, entry.toolCall.status)
    }

    /**
     * The tolerance requirement, tested rather than trusted. F-01 found a large
     * undocumented notification set, so this is the expected path for anything Kiro
     * adds after this build ships.
     */
    @Test
    fun `an unknown update kind renders as a generic entry and is counted`() {
        val counted = mutableListOf<String>()
        val counting = TranscriptReducer(object : DriftMetrics {
            override fun parseFailure(reason: String) = Unit
            override fun unknownMethod(method: String) = Unit
            override fun unknownUpdateKind(kind: String) { counted += kind }
        })

        val state = counting.reduce(
            TranscriptReducer.State(),
            SessionUpdate.Unrecognised("s", "session_info_update", "some_future_kind"),
        )

        assertEquals("some_future_kind", state.entries.filterIsInstance<TranscriptEntry.Unknown>().single().kind)
        assertEquals(listOf("some_future_kind"), counted)
    }

    @Test
    fun `the streaming message stays out of the entry list while it streams`() {
        val state = reducer.reduceAll(
            TranscriptReducer.State(),
            listOf(
                SessionUpdate.TurnStarted("s"),
                SessionUpdate.AgentMessageChunk("s", "half a ", null),
                SessionUpdate.AgentMessageChunk("s", "sentence", null),
            ),
        )
        // ADR-003 §3: the in-flight node renders outside the lazy list and is
        // appended only at turn end. If it ever appears in entries early, the list
        // re-lays-out on every chunk.
        assertTrue(state.entries.isEmpty())
        assertEquals("half a sentence", state.streamingText)
        assertTrue(state.isStreaming)
    }

    @Test
    fun `a permission request in the fixture arrives as a server-initiated request`() {
        val requests = Fixtures.load("prompt-turn-with-permission.jsonl")
            .filter { it.isFromAgent }
            .mapNotNull { it.decoded as? RpcRequest }
        val permission = requests.single { it.method == "session/request_permission" }

        val parsed = assertNotNull(
            dev.kiro.core.acp.PermissionParser.parse(rpcId = 1, params = permission.params),
        )
        assertEquals("shell", parsed.consent?.capability)
        assertEquals("id -un", parsed.consent?.resource)
        assertTrue(parsed.consent?.isImplicit == true)

        // The summary is what a notification has to fit and what TalkBack reads.
        // toolCall.title carries the *session* title in the captured frames, so a
        // client that used it would announce the wrong thing entirely.
        assertEquals("id -un", parsed.summary)
    }

    @Test
    fun `a duplicate tool call started event updates existing tool call rather than creating duplicate entries`() {
        val tool1 = ToolCall("t1", "pytest", "execute", ToolCall.Status.PENDING, emptyMap(), emptyList())
        val tool1Updated =
            ToolCall("t1", "pytest (retry)", "execute", ToolCall.Status.IN_PROGRESS, emptyMap(), emptyList())
        val state = reducer.reduceAll(
            TranscriptReducer.State(),
            listOf(
                SessionUpdate.ToolCallStarted("s", tool1),
                SessionUpdate.ToolCallStarted("s", tool1Updated),
            ),
        )
        assertEquals(1, state.entries.size)
        val entry = state.entries.filterIsInstance<TranscriptEntry.ToolCallEntry>().single()
        assertEquals("t1", entry.toolCall.toolCallId)
        assertEquals(ToolCall.Status.IN_PROGRESS, entry.toolCall.status)
        assertEquals("pytest (retry)", entry.toolCall.title)
    }
}
