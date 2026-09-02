package dev.kiro.core.model

/**
 * One row in the transcript.
 *
 * Modelled as a closed set of *rendered* shapes rather than as a mirror of the
 * wire protocol, because the wire protocol is open-ended: F-01 found seven update
 * kinds where the docs list four, plus an undocumented and growing `_kiro/…`
 * notification set. [Unknown] is what keeps a protocol addition a cosmetic gap
 * instead of a crash (ADR-003 §3).
 */
public sealed interface TranscriptEntry {
    public val id: String

    public data class UserMessage(
        override val id: String,
        val text: String,
    ) : TranscriptEntry

    public data class AgentMessage(
        override val id: String,
        val text: String,
        /** False while chunks are still arriving for this message. */
        val isComplete: Boolean,
    ) : TranscriptEntry

    public data class ToolCallEntry(
        override val id: String,
        val toolCall: ToolCall,
    ) : TranscriptEntry

    /** An in-band, user-facing error — e.g. an MCP server needing authorization. */
    public data class Error(
        override val id: String,
        val message: String,
        val errorType: String?,
    ) : TranscriptEntry

    /** Per-turn credit spend and tools used (F-19b). */
    public data class TurnSummary(
        override val id: String,
        val credits: Double?,
        val unit: String?,
        val elapsedMillis: Long?,
        val usedTools: List<String>,
    ) : TranscriptEntry

    /**
     * An update this build does not understand. Rendered as a generic entry with a
     * muted header, never dropped silently — a gap the user can see beats a hole
     * they cannot.
     */
    public data class Unknown(
        override val id: String,
        val kind: String,
    ) : TranscriptEntry
}

public data class ToolCall(
    val toolCallId: String,
    val title: String?,
    val kind: String?,
    val status: Status,
    val rawInput: Map<String, String>,
    val output: List<String>,
) {
    public enum class Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        UNKNOWN,
        ;

        public val isTerminal: Boolean get() = this == COMPLETED || this == FAILED

        public companion object {
            public fun fromWire(value: String?): Status = when (value) {
                "pending" -> PENDING
                "in_progress", "running" -> IN_PROGRESS
                "completed", "success" -> COMPLETED
                "failed", "error" -> FAILED
                else -> UNKNOWN
            }
        }
    }
}

/** Context-window pressure, surfaced so a stall has a visible reason. */
public data class ContextUsage(
    val usagePercentage: Double,
)
