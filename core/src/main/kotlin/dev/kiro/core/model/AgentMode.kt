package dev.kiro.core.model

/**
 * An agent mode, as offered by `config_option_update` / `session/new`.
 *
 * FEATURES calls this "autonomy level" and lists Autopilot/Autonomous. F-01 found
 * the real axis is Kiro's agent modes — `vibe`, `spec`, `quick-spec`, `bug-fix`,
 * `plan`, `autonomous` — so this type carries what the agent actually sends and
 * lets the UI label it, rather than inventing a parallel enum that has to be kept
 * in sync with a list only the server knows.
 */
public data class AgentMode(
    val id: String,
    val name: String,
    val description: String?,
)

/** A selectable model, from the same `config_option_update` channel. */
public data class KiroModel(
    val id: String,
    val name: String,
    val description: String?,
)

/**
 * One `config_option_update` select, generically. The two the app cares about are
 * `mode` and `model`; anything else is carried without being understood so a new
 * option can be surfaced without a client release.
 */
public data class ConfigOption(
    val id: String,
    val name: String,
    val category: String?,
    val currentValue: String?,
    val options: List<Choice>,
) {
    public data class Choice(
        val value: String,
        val name: String,
        val description: String?,
    )
}
