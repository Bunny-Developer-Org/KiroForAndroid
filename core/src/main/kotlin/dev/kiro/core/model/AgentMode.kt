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

/**
 * A selectable model, from the same `config_option_update` channel.
 *
 * [rateMultiplier] and [rateUnit] come from the choice's `_meta.kiro` block —
 * `{"rateMultiplier": 2.2, "rateUnit": "Credit"}` — and are the only place the
 * protocol says what a model costs. Both are nullable because the block is
 * optional: an agent that omits it leaves the app with a model it can name but
 * cannot price, which must render as "no figure", never as "free"
 * (PROTOCOL-FINDINGS §4d).
 */
public data class KiroModel(
    val id: String,
    val name: String,
    val description: String?,
    val rateMultiplier: Double? = null,
    val rateUnit: String? = null,
)

/**
 * What is known about one session's model choice.
 *
 * "We have not been told" is a first-class state, not an empty list pretending to
 * be an answer. PROTOCOL-FINDINGS §4d establishes that a **cloud** session's
 * `session/new` and `session/load` results carry no `configOptions` at all — the
 * sandbox pushes them later over `config_option_update` — so between attaching to
 * a cloud session and the first of those notifications arriving, the honest
 * answer is [Unknown]. A UI must be able to say "not known yet" rather than
 * render an empty picker or invent a default.
 */
public data class ModelSelection(
    val available: List<KiroModel> = emptyList(),
    val currentId: String? = null,
) {
    /** The current model, when it is one the agent also listed. */
    val current: KiroModel? get() = available.firstOrNull { it.id == currentId }

    /** False until the agent has sent a `model` config option for this session. */
    val isKnown: Boolean get() = available.isNotEmpty() || currentId != null

    /** True when a picker has something to show. */
    val hasCatalog: Boolean get() = available.isNotEmpty()

    public companion object {
        /** No `model` config option has been seen for this session. */
        public val Unknown: ModelSelection = ModelSelection()
    }
}

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
        /** `_meta.kiro.rateMultiplier` — present on model choices, absent elsewhere. */
        val rateMultiplier: Double? = null,
        /** `_meta.kiro.rateUnit`, e.g. `"Credit"`. */
        val rateUnit: String? = null,
    )

    public companion object {
        /**
         * The config ids KAS uses. Verified 2026-09-03 by reading the shipped
         * `@kiro/agent` bundle, which declares them as `MODEL_CONFIG_ID = "model"`
         * and `MODE_CONFIG_ID = "mode"` and dispatches
         * `session/set_config_option` on them.
         */
        public const val MODEL: String = "model"
        public const val MODE: String = "mode"
    }
}
