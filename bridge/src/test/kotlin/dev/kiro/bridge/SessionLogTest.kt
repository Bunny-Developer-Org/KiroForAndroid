package dev.kiro.bridge

import dev.kiro.core.acp.RpcNotification
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * ACP-INTEGRATION §7 designed a bridge-local sequence-number scheme and asked the
 * implementer to re-open the question first. Re-opened: all 57 updates in the
 * captured cloud replay carry `_meta.kiro.messageId`, so resume keys off that.
 * What the bridge still owes is ordering and an honest answer when the requested
 * point is gone.
 */
class SessionLogTest {

    private fun update(sessionId: String, messageId: String) = RpcNotification(
        method = "session/update",
        params = buildJsonObject {
            put("sessionId", sessionId)
            put(
                "update",
                buildJsonObject {
                    put("sessionUpdate", "agent_message_chunk")
                    put(
                        "_meta",
                        buildJsonObject { put("kiro", buildJsonObject { put("messageId", messageId) }) },
                    )
                },
            )
        },
    )

    @Test
    fun `replays exactly what came after the requested message`() {
        val log = SessionLog(capacity = 100)
        listOf("a", "b", "c", "d").forEach { log.record("s1", update("s1", it)) }

        val replay = assertIs<SessionLog.Replay.From>(log.replay("s1", afterMessageId = "b"))
        assertEquals(2, replay.entries.size)
        assertEquals(listOf("c", "d"), replay.entries.map { it.messageId })
    }

    @Test
    fun `a null cursor replays the whole session`() {
        val log = SessionLog(capacity = 100)
        listOf("a", "b").forEach { log.record("s1", update("s1", it)) }
        val replay = assertIs<SessionLog.Replay.From>(log.replay("s1", afterMessageId = null))
        assertEquals(2, replay.entries.size)
    }

    /**
     * The case ADR-005 §5.3 makes acceptance criteria: an honest full refetch
     * rather than a silent hole in the transcript.
     */
    @Test
    fun `a cursor evicted by the buffer reports truncation instead of a gap`() {
        val log = SessionLog(capacity = 3)
        listOf("a", "b", "c", "d", "e").forEach { log.record("s1", update("s1", it)) }

        assertIs<SessionLog.Replay.Truncated>(log.replay("s1", afterMessageId = "a"))
        assertIs<SessionLog.Replay.From>(log.replay("s1", afterMessageId = "d"))
    }

    /**
     * Bridges are fungible because sessions live in the Kiro account, but the
     * replay log is bridge-local. Switching bridges mid-session is a defined case,
     * not corruption.
     */
    @Test
    fun `a session this bridge never saw reports truncation`() {
        val log = SessionLog(capacity = 10)
        assertIs<SessionLog.Replay.Truncated>(log.replay("never-seen", afterMessageId = "x"))
    }

    @Test
    fun `sessions do not bleed into each other`() {
        val log = SessionLog(capacity = 10)
        log.record("s1", update("s1", "a"))
        log.record("s2", update("s2", "b"))

        assertEquals(
            listOf("a"),
            assertIs<SessionLog.Replay.From>(log.replay("s1", null)).entries.map { it.messageId },
        )
        assertEquals(2, log.sessionCount())
    }
}
