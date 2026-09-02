package dev.kiro.core.session

import dev.kiro.core.acp.SessionUpdate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FakeGatewayTest {

    @Test
    fun `multiple prompt calls produce unique tool call ids and reply ids`() = runTest {
        val gateway = FakeGateway()
        val updates = mutableListOf<SessionUpdate>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gateway.updates.toList(updates)
        }

        gateway.prompt("s1", listOf(PromptBlock.Text("first message")))
        gateway.prompt("s1", listOf(PromptBlock.Text("second message")))

        val toolStarts = updates.filterIsInstance<SessionUpdate.ToolCallStarted>()
        assertEquals(2, toolStarts.size)
        assertNotEquals(toolStarts[0].toolCall.toolCallId, toolStarts[1].toolCall.toolCallId)

        val agentChunks = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
        val replayIds = agentChunks.map { it.replayId }.distinct()
        assertEquals(2, replayIds.size)

        collector.cancel()
    }

    @Test
    fun `respondToPermission updates tool call status`() = runTest {
        val gateway = FakeGateway()
        val updates = mutableListOf<SessionUpdate>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gateway.updates.toList(updates)
        }

        gateway.respondToPermission("s1", "fake-tool-1", "accept")
        gateway.respondToPermission("s1", "fake-tool-2", "reject")

        val toolUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdated>()
        assertEquals(2, toolUpdates.size)
        assertEquals(dev.kiro.core.model.ToolCall.Status.COMPLETED, toolUpdates[0].status)
        assertEquals(dev.kiro.core.model.ToolCall.Status.FAILED, toolUpdates[1].status)

        collector.cancel()
    }

    @Test
    fun `deleteSession removes the session and pushes a roster deletion`() = runTest {
        val gateway = FakeGateway()
        val before = gateway.listSessions()
        val target = before.first()

        val rosterChanges = mutableListOf<RosterChange>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            gateway.rosterChanges.toList(rosterChanges)
        }

        gateway.deleteSession(target.id)

        val after = gateway.listSessions()
        assertFalse(after.any { it.id == target.id }, "deleted session must not be listed again")
        assertEquals(before.size - 1, after.size)

        assertEquals(1, rosterChanges.size)
        assertEquals(listOf(target.id), rosterChanges.single().deleted)
        assertTrue(rosterChanges.single().upserted.isEmpty())

        collector.cancel()
    }
}
