package dev.kiro.bridge

import dev.kiro.core.acp.RpcNotification
import dev.kiro.core.acp.kiroMeta
import dev.kiro.core.acp.str
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session replay, keyed by the agent's own `messageId`.
 *
 * ACP-INTEGRATION §7 designed a `_bridge/…` sequence-number scheme, and told the
 * implementer to re-open the question first. Re-opened: every one of the 57
 * updates in the captured cloud replay carries `_meta.kiro.messageId`, so a
 * parallel numbering scheme would be a second identifier for something the agent
 * already identifies. **Decision: resume by `messageId`.**
 *
 * What the bridge still owes is *ordering and retention*, which the id alone does
 * not give — hence a bounded ordered log per session, and an explicit "I no
 * longer have that point" answer rather than a silent gap.
 */
public class SessionLog(private val capacity: Int) {

    private val logs = ConcurrentHashMap<String, ArrayDeque<Entry>>()

    public data class Entry(val messageId: String?, val notification: RpcNotification)

    public sealed interface Replay {
        /** Everything after the requested point, in order. */
        public data class From(val entries: List<Entry>) : Replay

        /**
         * The requested point has been evicted, or belongs to a session this bridge
         * never saw — which is the normal case after switching bridges, since the
         * log is bridge-local while sessions live in the Kiro account.
         *
         * The app must refetch from scratch. An honest full refetch beats a
         * silent hole (ADR-005 §5.3).
         */
        public data object Truncated : Replay
    }

    public fun record(sessionId: String, notification: RpcNotification) {
        val messageId = notification.params.kiroMeta()?.str("messageId")
            ?: notification.params.updateMeta()?.str("messageId")
        val queue = logs.getOrPut(sessionId) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(Entry(messageId, notification))
            while (queue.size > capacity) queue.removeFirst()
        }
    }

    public fun replay(sessionId: String, afterMessageId: String?): Replay {
        val queue = logs[sessionId] ?: return Replay.Truncated
        synchronized(queue) {
            if (afterMessageId == null) return Replay.From(queue.toList())
            val index = queue.indexOfFirst { it.messageId == afterMessageId }
            if (index < 0) return Replay.Truncated
            return Replay.From(queue.drop(index + 1))
        }
    }

    public fun forget(sessionId: String) {
        logs.remove(sessionId)
    }

    public fun sessionCount(): Int = logs.size
}

/** `params.update._meta.kiro`, where a `session/update` puts its message id. */
private fun kotlinx.serialization.json.JsonElement?.updateMeta():
    kotlinx.serialization.json.JsonObject? =
    (this as? kotlinx.serialization.json.JsonObject)
        ?.get("update")
        ?.kiroMeta()
