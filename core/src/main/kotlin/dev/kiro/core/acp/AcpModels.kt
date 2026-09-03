package dev.kiro.core.acp

import dev.kiro.core.model.AgentMode
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ConfigOption
import dev.kiro.core.model.ContextUsage
import dev.kiro.core.model.ExecutionTarget
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.KiroModel
import dev.kiro.core.model.ModelSelection
import dev.kiro.core.model.PermissionOption
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SessionStatus
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.model.SourceRepo
import dev.kiro.core.model.ToolCall
import dev.kiro.core.model.UserInputRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire → domain parsing.
 *
 * Every function here is total: it returns something for any input, and reaches
 * for an `Unknown` variant rather than throwing. That is not defensive style, it
 * is the requirement — F-01 found the documented update list to be both wrong and
 * incomplete, so "a shape we have never seen" is the expected case, not the edge.
 */

// ---------------------------------------------------------------------------
// Handshake
// ---------------------------------------------------------------------------

public data class InitializeResult(
    val protocolVersion: Int,
    val loadSession: Boolean,
    val supportsImages: Boolean,
    val supportsSessionList: Boolean,
    val extensionMethods: List<String>,
    val sessionSources: List<String>,
    val sessionListScopes: List<String>,
    val executionTargets: List<String>,
    val authMethods: List<AuthMethod>,
) {
    val namespace: ExtensionNamespace get() = ExtensionNamespace.from(extensionMethods)

    /**
     * Whether this agent can place a session in Kiro's cloud sandbox at all.
     *
     * The runtime signal that we are not talking to a local-only agent. When this
     * is false the app must say so plainly rather than letting session creation
     * fail with something opaque.
     */
    val supportsCloudSessions: Boolean
        get() = executionTargets.contains(ExecutionTarget.CLOUD_SANDBOX.wire)

    public data class AuthMethod(val id: String, val name: String?, val description: String?)

    public companion object {
        public fun parse(result: JsonElement?): InitializeResult? {
            val root = result as? JsonObject ?: return null
            val caps = root.obj("agentCapabilities")
            val kiro = caps?.obj("_meta")?.obj("kiro")
            return InitializeResult(
                protocolVersion = root.num("protocolVersion")?.toInt() ?: 1,
                loadSession = caps?.bool("loadSession") ?: false,
                supportsImages = caps?.obj("promptCapabilities")?.bool("image") ?: false,
                supportsSessionList = caps?.obj("sessionCapabilities")?.containsKey("list") ?: false,
                extensionMethods = kiro.stringList("extensionMethods"),
                sessionSources = kiro.stringList("sessionSources"),
                sessionListScopes = kiro.stringList("sessionListScopes"),
                executionTargets = kiro.stringList("executionTargets"),
                authMethods = (root["authMethods"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val id = obj.str("id") ?: return@mapNotNull null
                    AuthMethod(id, obj.str("name"), obj.str("description"))
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Session roster
// ---------------------------------------------------------------------------

public object SessionParser {

    public fun parseList(result: JsonElement?): List<CloudSession> {
        val sessions = (result as? JsonObject)?.get("sessions") as? JsonArray ?: return emptyList()
        return sessions.mapNotNull { parseSession(it) }
    }

    public fun parseSession(element: JsonElement?): CloudSession? {
        val obj = element as? JsonObject ?: return null
        val id = obj.str("sessionId") ?: return null
        // Roster entries put everything in _meta.kiro; the push notification
        // (_kiro/sessions/changed) puts the same fields at the top level. Read both
        // so one parser serves both paths.
        val kiro = obj.obj("_meta")?.obj("kiro")
        val field: (String) -> String? = { key -> kiro?.str(key) ?: obj.str(key) }

        val target = (kiro?.obj("executionTarget") ?: obj.obj("executionTarget"))?.str("kind")
        return CloudSession(
            id = id,
            title = obj.str("title"),
            source = SessionSource.fromWire(field("source")),
            executionTarget = ExecutionTarget.fromWire(target),
            status = SessionStatus.fromWire(field("status")),
            instanceStatus = InstanceStatus.fromWire(field("instanceStatus")),
            repositories = (kiro?.get("repositories") as? JsonArray).orEmpty().mapNotNull { repo ->
                val r = repo as? JsonObject ?: return@mapNotNull null
                SourceRepo(
                    providerType = r.str("providerType") ?: return@mapNotNull null,
                    name = r.str("name") ?: return@mapNotNull null,
                    url = r.str("url"),
                )
            },
            agentMode = field("agentMode"),
            createdAt = field("createdAt"),
            updatedAt = obj.str("updatedAt"),
            cwd = obj.str("cwd"),
        )
    }

    /** `_kiro/sessions/changed` — the roster pushing itself, so F-10 need not poll. */
    public fun parseRosterChange(params: JsonElement?): RosterChange {
        val obj = params as? JsonObject ?: return RosterChange(emptyList(), emptyList())
        val upserted = (obj["upserted"] as? JsonArray).orEmpty().mapNotNull { parseSession(it) }
        val deleted = (obj["deleted"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: (it as? JsonObject)?.str("sessionId")
        }
        return RosterChange(upserted, deleted)
    }

    public data class RosterChange(
        val upserted: List<CloudSession>,
        val deleted: List<String>,
    )
}

public object RepoCatalogParser {

    public fun parseProviders(result: JsonElement?): List<SourceProvider> {
        val root = result as? JsonObject ?: return emptyList()
        val array = (root["providers"] ?: root["sourceProviders"]) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val type = obj.str("providerType") ?: return@mapNotNull null
            SourceProvider(
                providerType = type,
                displayName = obj.str("name") ?: obj.str("displayName"),
                connectionStatus = SourceProvider.ConnectionStatus.fromWire(obj.str("connectionStatus")),
            )
        }
    }

    public fun parseResources(result: JsonElement?): List<RepoCandidate> {
        val array = (result as? JsonObject)?.get("resources") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            RepoCandidate(
                providerType = obj.str("providerType") ?: return@mapNotNull null,
                name = obj.str("name") ?: return@mapNotNull null,
                url = obj.str("url"),
                visibility = obj.str("visibility"),
                defaultBranch = obj.str("defaultBranch"),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Streaming updates
// ---------------------------------------------------------------------------

/**
 * A parsed `session/update`.
 *
 * Note what is *not* here: a `TurnEnd` update kind. Turn boundaries arrive inside
 * `session_info_update`, discriminated by `_meta.kiro.kind`. A client written from
 * the published docs waits for an update kind that never comes.
 */
public sealed interface SessionUpdate {
    public val sessionId: String

    public data class AgentMessageChunk(
        override val sessionId: String,
        val text: String,
        /** Groups the chunks of one logical message. */
        val replayId: String?,
    ) : SessionUpdate

    public data class UserMessageChunk(
        override val sessionId: String,
        val text: String,
    ) : SessionUpdate

    public data class ToolCallStarted(
        override val sessionId: String,
        val toolCall: ToolCall,
    ) : SessionUpdate

    public data class ToolCallUpdated(
        override val sessionId: String,
        val toolCallId: String,
        val status: ToolCall.Status,
        val title: String?,
        val output: List<String>,
    ) : SessionUpdate

    public data class TurnStarted(override val sessionId: String) : SessionUpdate

    public data class TurnEnded(
        override val sessionId: String,
        val stopReason: String?,
    ) : SessionUpdate

    public data class TurnCompleted(
        override val sessionId: String,
        val credits: Double?,
        val unit: String?,
        val elapsedMillis: Long?,
        val usedTools: List<String>,
    ) : SessionUpdate

    public data class ContextUsageChanged(
        override val sessionId: String,
        val usage: ContextUsage,
    ) : SessionUpdate

    /** The agent retitling the session mid-turn. */
    public data class TitleChanged(
        override val sessionId: String,
        val title: String,
    ) : SessionUpdate

    /** An approval is outstanding — render the waiting state even before the request arrives. */
    public data class PendingInteraction(
        override val sessionId: String,
        val toolCallId: String,
        val interactionType: String?,
        val question: String?,
        val options: List<PermissionOption>,
    ) : SessionUpdate

    /**
     * It was answered — possibly by another client, which is why this exists as a
     * stream event rather than only as our own response completing.
     */
    public data class InteractionResolved(
        override val sessionId: String,
        val toolCallId: String,
        val outcome: String?,
        val selectedOption: String?,
    ) : SessionUpdate

    public data class DisplayError(
        override val sessionId: String,
        val message: String,
        val errorType: String?,
    ) : SessionUpdate

    public data class ConfigOptionsChanged(
        override val sessionId: String,
        val options: List<ConfigOption>,
    ) : SessionUpdate

    public data class AvailableCommandsChanged(
        override val sessionId: String,
        val commands: List<Command>,
    ) : SessionUpdate {
        public data class Command(val name: String, val description: String?)
    }

    /**
     * Anything else. Carries enough to render a generic entry and to be counted by
     * the drift metric F-22 makes the trigger for revisiting ADR-002.
     */
    public data class Unrecognised(
        override val sessionId: String,
        val sessionUpdate: String?,
        val kind: String?,
    ) : SessionUpdate
}

public object SessionUpdateParser {

    /** Parses the `params` of a `session/update` notification. */
    @Suppress("CyclomaticComplexMethod")
    public fun parse(params: JsonElement?): SessionUpdate? {
        val root = params as? JsonObject ?: return null
        val sessionId = root.str("sessionId") ?: return null
        val update = root.obj("update") ?: return null
        val kiro = update.obj("_meta")?.obj("kiro")

        return when (update.str("sessionUpdate")) {
            "agent_message_chunk" -> SessionUpdate.AgentMessageChunk(
                sessionId = sessionId,
                text = update.contentText(),
                replayId = kiro?.str("replayId"),
            )

            "user_message_chunk" -> SessionUpdate.UserMessageChunk(sessionId, update.contentText())

            "tool_call" -> SessionUpdate.ToolCallStarted(sessionId, parseToolCall(update) ?: return null)

            "tool_call_update" -> SessionUpdate.ToolCallUpdated(
                sessionId = sessionId,
                toolCallId = update.str("toolCallId") ?: return null,
                status = ToolCall.Status.fromWire(update.str("status")),
                title = update.str("title"),
                output = update.contentList(),
            )

            "config_option_update" -> SessionUpdate.ConfigOptionsChanged(
                sessionId = sessionId,
                options = ConfigOptionParser.parse(update),
            )

            "available_commands_update" -> SessionUpdate.AvailableCommandsChanged(
                sessionId = sessionId,
                commands = (update["availableCommands"] as? JsonArray).orEmpty().mapNotNull {
                    val obj = it as? JsonObject ?: return@mapNotNull null
                    SessionUpdate.AvailableCommandsChanged.Command(
                        name = obj.str("name") ?: return@mapNotNull null,
                        description = obj.str("description"),
                    )
                },
            )

            "session_info_update" -> parseSessionInfo(sessionId, update, kiro)

            else -> SessionUpdate.Unrecognised(sessionId, update.str("sessionUpdate"), kiro?.str("kind"))
        }
    }

    /**
     * `session_info_update` carries most session state, discriminated by
     * `_meta.kiro.kind`.
     *
     * The wire spellings are `snake_case` — `pending_interaction`,
     * `interaction_resolved`, `display_error`, `turn_completion`. ACP-INTEGRATION §4
     * lists four of them in camelCase, which does not match what 2.19.2 sends;
     * both spellings are accepted here so that correcting the document, or Kiro
     * changing its mind, is not a client bug either way.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun parseSessionInfo(
        sessionId: String,
        update: JsonObject,
        kiro: JsonObject?,
    ): SessionUpdate {
        val kind = kiro?.str("kind")
        return when (kind) {
            "turn_start", "turnStart" -> SessionUpdate.TurnStarted(sessionId)

            "turn_end", "turnEnd" -> SessionUpdate.TurnEnded(
                sessionId = sessionId,
                stopReason = kiro.str("stopReason") ?: kiro.obj("turnEnd")?.str("stopReason"),
            )

            "turn_completion", "turnCompletion", "promptTurnSummaries" -> {
                val summary = (kiro.get("promptTurnSummaries") as? JsonArray)
                    ?.firstOrNull() as? JsonObject
                SessionUpdate.TurnCompleted(
                    sessionId = sessionId,
                    credits = summary?.num("usage"),
                    unit = summary?.str("unit"),
                    elapsedMillis = kiro.num("elapsedTime")?.toLong(),
                    usedTools = (summary?.get("usedTools") as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                    },
                )
            }

            "context_usage", "contextUsage" -> SessionUpdate.ContextUsageChanged(
                sessionId = sessionId,
                usage = ContextUsage(kiro.num("usagePercentage") ?: 0.0),
            )

            "focus_update", "focusUpdate" -> SessionUpdate.TitleChanged(
                sessionId = sessionId,
                title = kiro.str("title") ?: update.str("title").orEmpty(),
            )

            "pending_interaction", "pendingInteraction" -> {
                val pending = kiro.obj("pendingInteraction")
                SessionUpdate.PendingInteraction(
                    sessionId = sessionId,
                    toolCallId = pending?.str("toolCallId") ?: kiro.str("toolCallId").orEmpty(),
                    interactionType = pending?.str("interactionType"),
                    question = pending?.str("question"),
                    options = parseOptions(pending?.get("options") as? JsonArray),
                )
            }

            "interaction_resolved", "interactionResolved" -> {
                val resolved = kiro.obj("interactionResolved")
                SessionUpdate.InteractionResolved(
                    sessionId = sessionId,
                    toolCallId = resolved?.str("toolCallId") ?: kiro.str("toolCallId").orEmpty(),
                    outcome = resolved?.str("outcome") ?: kiro.str("outcome"),
                    selectedOption = resolved?.str("selectedOption") ?: kiro.str("selectedOption"),
                )
            }

            "display_error", "displayError" -> SessionUpdate.DisplayError(
                sessionId = sessionId,
                message = kiro.str("message")
                    ?: kiro.obj("displayError")?.str("message").orEmpty(),
                errorType = kiro.str("errorType") ?: kiro.obj("displayError")?.str("errorType"),
            )

            else -> SessionUpdate.Unrecognised(sessionId, "session_info_update", kind)
        }
    }

    private fun parseToolCall(update: JsonObject): ToolCall? = ToolCall(
        toolCallId = update.str("toolCallId") ?: return null,
        title = update.str("title"),
        kind = update.str("kind"),
        status = ToolCall.Status.fromWire(update.str("status")),
        rawInput = update.obj("rawInput").orEmpty().mapValues { (_, value) ->
            (value as? JsonPrimitive)?.content ?: value.toString()
        },
        output = update.contentList(),
    )

    internal fun parseOptions(array: JsonArray?): List<PermissionOption> =
        array.orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            PermissionOption(
                optionId = obj.str("optionId") ?: return@mapNotNull null,
                name = obj.str("name") ?: obj.str("optionId").orEmpty(),
                kind = PermissionOption.Kind.fromWire(obj.str("kind")),
            )
        }
}

/**
 * Config options, wherever they turn up.
 *
 * They arrive from four places and the shape is identical in all four, so one
 * parser serves them all: the `session/new` result, the `session/load` result,
 * a `config_option_update` notification, and the `session/set_config_option`
 * response (PROTOCOL-FINDINGS §4d).
 *
 * **A cloud session sends them only in the notification.** KAS's relayed
 * `session/new` and `session/load` responses omit `modes` and `configOptions`
 * outright — the sandbox owns the agent surface and pushes it over
 * `config_option_update` once it is up. Parsing the result is still worth doing
 * (a local session does carry them), but a caller must treat "nothing here" as
 * the normal cloud case rather than a failure.
 */
public object ConfigOptionParser {

    /**
     * Reads a `configOptions` array off [container], which may be a `session/new`
     * or `session/load` result, a `session/update` update object, or a
     * `session/set_config_option` response. Anything else yields an empty list
     * rather than throwing.
     */
    public fun parse(container: JsonElement?): List<ConfigOption> {
        val root = container as? JsonObject ?: return emptyList()
        return (root["configOptions"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            ConfigOption(
                id = obj.str("id") ?: return@mapNotNull null,
                name = obj.str("name") ?: return@mapNotNull null,
                category = obj.str("category"),
                currentValue = obj.str("currentValue"),
                options = (obj["options"] as? JsonArray).orEmpty().mapNotNull { choice ->
                    val c = choice as? JsonObject ?: return@mapNotNull null
                    val kiro = c.obj("_meta")?.obj("kiro")
                    ConfigOption.Choice(
                        value = c.str("value") ?: return@mapNotNull null,
                        name = c.str("name") ?: c.str("value").orEmpty(),
                        description = c.str("description"),
                        rateMultiplier = kiro?.num("rateMultiplier"),
                        rateUnit = kiro?.str("rateUnit"),
                    )
                },
            )
        }
    }
}

/** Modes offered by `session/new` and by the `mode` config option. */
public fun ConfigOption.asAgentModes(): List<AgentMode> =
    options.map { AgentMode(it.value, it.name, it.description) }

/** The models offered by the `model` config option. */
public fun ConfigOption.asModels(): List<KiroModel> =
    options.map { KiroModel(it.value, it.name, it.description, it.rateMultiplier, it.rateUnit) }

/**
 * Finds the `model` select in a config-option set and reads it as a selection.
 *
 * Matches on `id == "model"` first and falls back to `category == "model"`: KAS
 * sets both, and matching either means a rename of one does not silently lose
 * the picker. Returns [ModelSelection.Unknown] when there is no model option at
 * all — which is the normal state of a cloud session that has not yet pushed
 * one, not an error.
 */
public fun List<ConfigOption>.modelSelection(): ModelSelection {
    val option = firstOrNull { it.id == ConfigOption.MODEL }
        ?: firstOrNull { it.category == ConfigOption.MODEL }
        ?: return ModelSelection.Unknown
    return ModelSelection(available = option.asModels(), currentId = option.currentValue)
}

/** The `mode` select, read as modes. Empty when the agent offered none. */
public fun List<ConfigOption>.agentModes(): List<AgentMode> =
    (firstOrNull { it.id == ConfigOption.MODE } ?: firstOrNull { it.category == ConfigOption.MODE })
        ?.asAgentModes()
        .orEmpty()

// ---------------------------------------------------------------------------
// Server-initiated requests
// ---------------------------------------------------------------------------

public object PermissionParser {

    /** Parses a `session/request_permission` request. */
    public fun parse(rpcId: Long?, params: JsonElement?): PermissionRequest? {
        val root = params as? JsonObject ?: return null
        val toolCall = root.obj("toolCall")
        val kiro = root.obj("_meta")?.obj("kiro")
        val consent = kiro?.obj("consent")
        return PermissionRequest(
            sessionId = root.str("sessionId") ?: return null,
            toolCallId = toolCall?.str("toolCallId") ?: return null,
            // The captured frames put the *session* title in toolCall.title, so the
            // command itself is only readable from consent metadata. Prefer that.
            title = kiro?.str("command") ?: toolCall.str("title"),
            options = SessionUpdateParser.parseOptions(root["options"] as? JsonArray),
            consent = consent?.let {
                PermissionRequest.Consent(
                    capability = it.str("capability"),
                    resource = it.str("resource"),
                    askType = it.str("askType"),
                    workspaceRoot = it.str("workspaceRoot"),
                )
            },
            rpcId = rpcId,
        )
    }

    /** Parses a `_kiro/userInput` request — the free-text channel. */
    public fun parseUserInput(params: JsonElement?): UserInputRequest? {
        val root = params as? JsonObject ?: return null
        return UserInputRequest(
            sessionId = root.str("sessionId") ?: return null,
            toolCallId = root.str("toolCallId") ?: return null,
            question = root.str("question") ?: root.str("prompt") ?: return null,
            placeholder = root.str("placeholder"),
        )
    }
}

// ---------------------------------------------------------------------------
// Small shared helpers
// ---------------------------------------------------------------------------

private fun JsonObject?.stringList(key: String): List<String> =
    (this?.get(key) as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
    }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

/** `content` is sometimes an object and sometimes an array of them. Handle both. */
private fun JsonObject.contentText(): String =
    obj("content")?.str("text")
        ?: (this["content"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("text") }
            ?.joinToString("")
        ?: ""

private fun JsonObject.contentList(): List<String> = when (val content = this["content"]) {
    is JsonArray -> content.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        obj.str("text") ?: obj.obj("content")?.str("text")
    }
    is JsonObject -> listOfNotNull(content.str("text"))
    else -> emptyList()
}
