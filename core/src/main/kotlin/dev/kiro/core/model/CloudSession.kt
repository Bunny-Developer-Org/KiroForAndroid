package dev.kiro.core.model

/**
 * A session as the roster describes it.
 *
 * The two statuses are independent and must stay that way all the way to the UI
 * (FEATURES F-10): [status] answers "is the agent working?", [instanceStatus]
 * answers "is the sandbox VM up?". "Idle but suspended" and "idle and warm" mean
 * very different wait times when you tap in, and only one of them is instant.
 */
public data class CloudSession(
    val id: String,
    val title: String?,
    val source: SessionSource,
    val executionTarget: ExecutionTarget,
    val status: SessionStatus,
    val instanceStatus: InstanceStatus,
    val repositories: List<SourceRepo>,
    val agentMode: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val cwd: String?,
) {
    val isCloud: Boolean get() = executionTarget == ExecutionTarget.CLOUD_SANDBOX

    /** True when tapping in means waiting for a VM to come back. */
    val needsWarmUp: Boolean get() = instanceStatus == InstanceStatus.SUSPENDED
}

public enum class SessionSource(public val wire: String) {
    LOCAL("local"),
    REMOTE("remote"),
    ALL("all"),
    ;

    public companion object {
        public fun fromWire(value: String?): SessionSource =
            entries.firstOrNull { it.wire == value } ?: LOCAL
    }
}

public enum class ListScope(public val wire: String) {
    WORKSPACE("workspace"),
    USER("user"),
    BOTH("both"),
}

public enum class ExecutionTarget(public val wire: String) {
    LOCAL("local"),
    CLOUD_SANDBOX("cloud-sandbox"),
    ;

    public companion object {
        public fun fromWire(value: String?): ExecutionTarget =
            entries.firstOrNull { it.wire == value } ?: LOCAL
    }
}

/** Is the agent working? */
public enum class SessionStatus {
    IDLE,
    IN_PROGRESS,

    /** The agent sent a status this build does not know. Render it, don't crash. */
    UNKNOWN,
    ;

    public companion object {
        public fun fromWire(value: String?): SessionStatus = when (value) {
            "idle" -> IDLE
            "in_progress", "inProgress" -> IN_PROGRESS
            null -> IDLE
            else -> UNKNOWN
        }
    }
}

/** Is the sandbox VM up? Absent on local sessions, which have no VM. */
public enum class InstanceStatus {
    RUNNING,
    SUSPENDED,
    NOT_APPLICABLE,
    UNKNOWN,
    ;

    public companion object {
        public fun fromWire(value: String?): InstanceStatus = when (value) {
            null -> NOT_APPLICABLE
            "running", "active" -> RUNNING
            "suspended" -> SUSPENDED
            else -> UNKNOWN
        }
    }
}
