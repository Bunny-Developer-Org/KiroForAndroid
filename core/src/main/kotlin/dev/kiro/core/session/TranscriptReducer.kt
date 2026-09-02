package dev.kiro.core.session

import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.model.ContextUsage
import dev.kiro.core.model.PermissionOption
import dev.kiro.core.model.ToolCall
import dev.kiro.core.model.TranscriptEntry
import dev.kiro.core.util.DriftMetrics

/**
 * Folds a stream of updates into something renderable.
 *
 * Two properties are load-bearing and both come straight from ADR-003 §3:
 *
 *  1. **The in-flight message is not in [entries].** It is [streamingText],
 *     rendered as a node *outside* the lazy list and appended only at turn end.
 *     Keeping a growing string out of a virtualised list is the difference
 *     between a smooth transcript and one that re-lays-out thousands of rows on
 *     every chunk.
 *  2. **An unknown update produces an entry, not an exception.** F-01 found the
 *     documented update list is both wrong and incomplete, so this is the normal
 *     path for anything Kiro adds after this build shipped.
 *
 * Pure and synchronous: no coroutines, no clock, no I/O. Feed it a fixture and
 * assert on the result.
 */
public class TranscriptReducer(
    private val metrics: DriftMetrics = DriftMetrics.None,
) {

    public data class State(
        val entries: List<TranscriptEntry> = emptyList(),
        /** The message currently streaming. Rendered outside the list. */
        val streamingText: String = "",
        val isTurnActive: Boolean = false,
        val title: String? = null,
        val contextUsage: ContextUsage? = null,
        /** An approval the user still owes an answer to, keyed by tool call. */
        val pendingApproval: PendingApproval? = null,
        val stopReason: String? = null,
    ) {
        val isStreaming: Boolean get() = streamingText.isNotEmpty()
    }

    public data class PendingApproval(
        val toolCallId: String,
        val question: String?,
        val options: List<PermissionOption>,
    )

    @Suppress("CyclomaticComplexMethod")
    public fun reduce(state: State, update: SessionUpdate): State = when (update) {

        is SessionUpdate.AgentMessageChunk ->
            state.copy(streamingText = state.streamingText + update.text)

        is SessionUpdate.UserMessageChunk -> state.appending(
            TranscriptEntry.UserMessage(
                id = "user-${state.entries.size}",
                text = update.text,
            ),
        )

        is SessionUpdate.ToolCallStarted -> state.appending(
            TranscriptEntry.ToolCallEntry(
                id = update.toolCall.toolCallId,
                toolCall = update.toolCall,
            ),
        )

        is SessionUpdate.ToolCallUpdated -> state.updatingToolCall(update)

        is SessionUpdate.TurnStarted -> state.copy(isTurnActive = true, stopReason = null)

        // Flushing here is what makes the streaming node's contents become a real
        // entry exactly once. A turn that ends without a chunk flushes nothing.
        is SessionUpdate.TurnEnded -> state.flushingStream().copy(
            isTurnActive = false,
            stopReason = update.stopReason,
        )

        is SessionUpdate.TurnCompleted -> state.appending(
            TranscriptEntry.TurnSummary(
                id = "summary-${state.entries.size}",
                credits = update.credits,
                unit = update.unit,
                elapsedMillis = update.elapsedMillis,
                usedTools = update.usedTools,
            ),
        )

        is SessionUpdate.ContextUsageChanged -> state.copy(contextUsage = update.usage)

        is SessionUpdate.TitleChanged -> state.copy(title = update.title)

        is SessionUpdate.PendingInteraction -> state.copy(
            pendingApproval = PendingApproval(
                toolCallId = update.toolCallId,
                question = update.question,
                options = update.options,
            ),
        )

        // Clearing on resolution is what handles *another* client answering first.
        // Matching on tool call id avoids clearing a newer approval with an older
        // resolution, which replay makes possible.
        is SessionUpdate.InteractionResolved ->
            if (state.pendingApproval?.toolCallId == update.toolCallId) {
                state.copy(pendingApproval = null)
            } else {
                state
            }

        is SessionUpdate.DisplayError -> state.appending(
            TranscriptEntry.Error(
                id = "error-${state.entries.size}",
                message = update.message,
                errorType = update.errorType,
            ),
        )

        // Config and command lists are session settings, not transcript content.
        // The gateway surfaces them separately; the transcript ignores them.
        is SessionUpdate.ConfigOptionsChanged -> state
        is SessionUpdate.AvailableCommandsChanged -> state

        is SessionUpdate.Unrecognised -> {
            val kind = update.kind ?: update.sessionUpdate ?: "unknown"
            metrics.unknownUpdateKind(kind)
            state.appending(
                TranscriptEntry.Unknown(id = "unknown-${state.entries.size}", kind = kind),
            )
        }
    }

    public fun reduceAll(state: State, updates: Iterable<SessionUpdate>): State =
        updates.fold(state, ::reduce)

    private fun State.appending(entry: TranscriptEntry): State =
        flushingStream().let { it.copy(entries = it.entries + entry) }

    /**
     * Moves the streaming buffer into the entry list.
     *
     * Called before appending anything else so that a tool call arriving mid-message
     * does not end up rendered *above* the text that preceded it — the interleaving
     * case ACP-INTEGRATION §9 asks the reducer's tests to cover.
     */
    private fun State.flushingStream(): State =
        if (streamingText.isEmpty()) {
            this
        } else {
            copy(
                entries = entries + TranscriptEntry.AgentMessage(
                    id = "agent-${entries.size}",
                    text = streamingText,
                    isComplete = true,
                ),
                streamingText = "",
            )
        }

    private fun State.updatingToolCall(update: SessionUpdate.ToolCallUpdated): State {
        val index = entries.indexOfLast {
            it is TranscriptEntry.ToolCallEntry && it.toolCall.toolCallId == update.toolCallId
        }
        // A tool_call_update for a call we never saw start. Happens when a socket
        // drops mid-turn and reconnects part-way through; synthesise the entry
        // rather than losing the result.
        if (index < 0) {
            return appending(
                TranscriptEntry.ToolCallEntry(
                    id = update.toolCallId,
                    toolCall = ToolCall(
                        toolCallId = update.toolCallId,
                        title = update.title,
                        kind = null,
                        status = update.status,
                        rawInput = emptyMap(),
                        output = update.output,
                    ),
                ),
            )
        }
        val existing = entries[index] as TranscriptEntry.ToolCallEntry
        val merged = existing.copy(
            toolCall = existing.toolCall.copy(
                status = update.status,
                title = update.title ?: existing.toolCall.title,
                output = existing.toolCall.output + update.output,
            ),
        )
        return copy(entries = entries.toMutableList().also { it[index] = merged })
    }
}
