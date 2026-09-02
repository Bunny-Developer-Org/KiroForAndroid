package dev.kiro.core.session

import dev.kiro.core.acp.AcpClient
import dev.kiro.core.acp.AcpMethods
import dev.kiro.core.acp.AcpRemoteException
import dev.kiro.core.acp.ExtensionNamespace
import dev.kiro.core.acp.InitializeResult
import dev.kiro.core.acp.PermissionParser
import dev.kiro.core.acp.RepoCatalogParser
import dev.kiro.core.acp.RpcError
import dev.kiro.core.acp.RpcId
import dev.kiro.core.acp.RpcRequest
import dev.kiro.core.acp.SessionParser
import dev.kiro.core.acp.SessionUpdate
import dev.kiro.core.acp.SessionUpdateParser
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ExecutionTarget
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.PermissionRequest
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SessionStatus
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.model.SourceRepo
import dev.kiro.core.model.UserInputRequest
import dev.kiro.core.util.DriftMetrics
import dev.kiro.core.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.minutes

/**
 * [CloudSessionGateway] over ACP, through the bridge.
 *
 * Everything cloud-specific about this class is *dispatch metadata*: the
 * `params._meta.kiro` block that selects which store a call reaches. Omitting it
 * silently means `sessionSource: "local"`, which is precisely how a naive client
 * concludes that cloud sessions are unreachable when they are not
 * (ACP-INTEGRATION §3).
 */
public class BridgeGateway(
    private val client: AcpClient,
    private val scope: CoroutineScope,
    private val logger: Logger = Logger.None,
    private val metrics: DriftMetrics = DriftMetrics.None,
) : CloudSessionGateway {

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: Flow<ConnectionState> = _connection.asStateFlow()

    private val _updates = MutableSharedFlow<SessionUpdate>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val updates: Flow<SessionUpdate> = _updates.asSharedFlow()

    private val _permissions = MutableSharedFlow<PermissionRequest>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * `replay = 1` on purpose. A permission request is durable session state, not
     * an event: KAS re-presents an outstanding one to whichever client attaches
     * next. A subscriber that arrives a moment late — the transcript screen opening
     * after the connection did — must still see it.
     */
    override val permissionRequests: Flow<PermissionRequest> = _permissions.asSharedFlow()

    private val _userInput = MutableSharedFlow<UserInputRequest>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val userInputRequests: Flow<UserInputRequest> = _userInput.asSharedFlow()

    private val _roster = MutableSharedFlow<RosterChange>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val rosterChanges: Flow<RosterChange> = _roster.asSharedFlow()

    private var handshake: InitializeResult? = null
    private var namespace: ExtensionNamespace = ExtensionNamespace.Default

    /** Performs the handshake and starts routing inbound traffic. */
    public suspend fun connect() {
        _connection.value = ConnectionState.Connecting
        try {
            client.start()
        } catch (e: Throwable) {
            _connection.value = ConnectionState.Disconnected
            throw e
        }
        startRouting()

        val result = try {
            client.initialize()
        } catch (e: AcpRemoteException) {
            _connection.value = ConnectionState.Rejected(e.error.message)
            throw e
        }

        handshake = result
        namespace = result.namespace
        _connection.value = ConnectionState.Connected(
            agentSupportsCloudSessions = result.supportsCloudSessions,
            supportsImages = result.supportsImages,
        )

        // Not fatal: a local-only agent is a usable agent, just not for this app's
        // headline feature. Say so once, here, rather than at every failed create.
        if (!result.supportsCloudSessions) {
            logger.warn("agent advertises no cloud-sandbox execution target")
        }
    }

    private fun startRouting() {
        scope.launch {
            client.notifications.collect { notification ->
                when (notification.method) {
                    AcpMethods.SESSION_UPDATE -> {
                        val update = SessionUpdateParser.parse(notification.params)
                        if (update == null) {
                            metrics.parseFailure("unparseable session/update")
                        } else {
                            if (update is SessionUpdate.Unrecognised) {
                                metrics.unknownUpdateKind(update.kind ?: update.sessionUpdate.orEmpty())
                            }
                            // A pending_interaction is the app's second route to an
                            // approval: it arrives in-stream even when the original
                            // server request went to a connection we no longer hold.
                            if (update is SessionUpdate.PendingInteraction) {
                                _permissions.emit(update.toPermissionRequest())
                            }
                            _updates.emit(update)
                        }
                    }

                    namespace.sessionsChanged -> {
                        val change = SessionParser.parseRosterChange(notification.params)
                        _roster.emit(RosterChange(change.upserted, change.deleted))
                    }

                    else -> {
                        // Undocumented and open-ended by design. Counted, not fatal.
                        metrics.unknownMethod(notification.method)
                        logger.debug("ignoring notification ${notification.method}")
                    }
                }
            }
        }

        scope.launch {
            client.serverRequests.collect { request -> routeServerRequest(request) }
        }
    }

    private suspend fun routeServerRequest(request: RpcRequest) {
        when (request.method) {
            AcpMethods.SESSION_REQUEST_PERMISSION -> {
                val rpcId = (request.id as? RpcId.Num)?.value
                val parsed = PermissionParser.parse(rpcId, request.params)
                if (parsed == null) {
                    metrics.parseFailure("unparseable permission request")
                    client.respondWithError(
                        request,
                        RpcError(
                            RpcError.INVALID_PARAMS,
                            "could not read permission request",
                        ),
                    )
                } else {
                    // Note what is deliberately *not* here: an auto-answer. The
                    // request is handed to the user and the JSON-RPC response is
                    // sent by respondToPermission, possibly on another connection
                    // entirely.
                    _permissions.emit(parsed)
                }
            }

            namespace.userInput -> {
                val parsed = PermissionParser.parseUserInput(request.params)
                if (parsed != null) _userInput.emit(parsed)
            }

            else -> {
                // We advertised fs and terminal as false, so any request for them is
                // the agent asking for something we told it we cannot do.
                metrics.unknownMethod(request.method)
                client.respondWithError(
                    request,
                    RpcError(
                        RpcError.METHOD_NOT_FOUND,
                        "${request.method} is not implemented by this client",
                    ),
                )
            }
        }
    }

    override suspend fun listSessions(source: SessionSource, scope: ListScope): List<CloudSession> {
        val result = client.request(
            AcpMethods.SESSION_LIST,
            buildJsonObject {
                putKiroMeta {
                    put("sessionSource", source.wire)
                    put("listScope", scope.wire)
                }
            },
        )
        return SessionParser.parseList(result)
    }

    override suspend fun createSession(request: CreateSessionRequest): CloudSession {
        val params = buildJsonObject {
            // ADR-004: a cloud session has no working directory, no checkout and no
            // local git. The empty cwd is not an oversight — the repositories below
            // are what the sandbox clones.
            put("cwd", "")
            put("mcpServers", buildJsonArray { })
            request.modeId?.let { put("agentMode", it) }
            putKiroMeta {
                // sessionSource and executionTarget are two spellings of one
                // decision and the agent rejects sending both. Send the target.
                put(
                    "executionTarget",
                    buildJsonObject { put("kind", ExecutionTarget.CLOUD_SANDBOX.wire) },
                )
                if (request.repositories.isNotEmpty()) {
                    put(
                        "repositories",
                        buildJsonArray {
                            request.repositories.forEach { add(buildJsonObject { put("name", it) }) }
                        },
                    )
                }
            }
        }

        val result = try {
            client.request(AcpMethods.SESSION_NEW, params, timeout = 3.minutes)
        } catch (e: AcpRemoteException) {
            throw e.asDomainFailure()
        }

        val sessionId = (result as? JsonObject)?.let { it.strOrNull("sessionId") }
            ?: throw CloudUnavailableException("session/new returned no sessionId")

        return CloudSession(
            id = sessionId,
            title = null,
            source = SessionSource.REMOTE,
            executionTarget = ExecutionTarget.CLOUD_SANDBOX,
            status = SessionStatus.IDLE,
            instanceStatus = InstanceStatus.RUNNING,
            repositories = request.repositories.map {
                SourceRepo("GITHUB", it, null)
            },
            agentMode = request.modeId,
            createdAt = null,
            updatedAt = null,
            cwd = "",
        )
    }

    override suspend fun loadSession(sessionId: String, source: SessionSource) {
        client.request(
            AcpMethods.SESSION_LOAD,
            buildJsonObject {
                put("sessionId", sessionId)
                put("cwd", "")
                put("mcpServers", buildJsonArray { })
                putKiroMeta { put("sessionSource", source.wire) }
            },
            // Replay is large -- 991 updates on one real session -- and arrives
            // before this resolves.
            timeout = 5.minutes,
        )
    }

    override suspend fun prompt(sessionId: String, blocks: List<PromptBlock>): String? {
        val result = client.request(
            AcpMethods.SESSION_PROMPT,
            buildJsonObject {
                put("sessionId", sessionId)
                put(
                    "prompt",
                    buildJsonArray {
                        blocks.forEach { block ->
                            add(
                                when (block) {
                                    is PromptBlock.Text -> buildJsonObject {
                                        put("type", "text")
                                        put("text", block.text)
                                    }
                                    is PromptBlock.Image -> buildJsonObject {
                                        put("type", "image")
                                        put("mimeType", block.mimeType)
                                        put("data", block.base64Data)
                                    }
                                },
                            )
                        }
                    },
                )
            },
            // No timeout. A turn can legitimately run for many minutes, and killing
            // it on a wall clock is a bug that only bites on the tasks people care
            // about most.
            timeout = null,
        )
        return (result as? JsonObject)?.strOrNull("stopReason")
    }

    override suspend fun cancel(sessionId: String) {
        client.notify(
            AcpMethods.SESSION_CANCEL,
            buildJsonObject { put("sessionId", sessionId) },
        )
    }

    override suspend fun setMode(sessionId: String, modeId: String) {
        client.request(
            AcpMethods.SESSION_SET_MODE,
            buildJsonObject {
                put("sessionId", sessionId)
                put("modeId", modeId)
            },
        )
    }

    override suspend fun setModel(sessionId: String, modelId: String) {
        client.request(
            AcpMethods.SESSION_SET_MODEL,
            buildJsonObject {
                put("sessionId", sessionId)
                put("modelId", modelId)
            },
        )
    }

    override suspend fun respondToPermission(
        sessionId: String,
        toolCallId: String,
        optionId: String,
    ) {
        client.request(
            namespace.permissionRespond,
            buildJsonObject {
                put("sessionId", sessionId)
                put("toolCallId", toolCallId)
                put("optionId", optionId)
            },
        )
    }

    override suspend fun respondToUserInput(
        sessionId: String,
        toolCallId: String,
        answer: String?,
    ) {
        client.request(
            namespace.userInputRespond,
            buildJsonObject {
                put("sessionId", sessionId)
                put("toolCallId", toolCallId)
                put("action", if (answer == null) "dismissed" else "answered")
                answer?.let { put("answer", it) }
            },
        )
    }

    override suspend fun deleteSession(sessionId: String) {
        client.request(
            namespace.sessionDelete,
            buildJsonObject {
                put("sessionId", sessionId)
                putKiroMeta { put("sessionSource", SessionSource.REMOTE.wire) }
            },
        )
    }

    override suspend fun listSourceProviders(): List<SourceProvider> {
        val result = client.request(namespace.sourceProvidersList)
        return RepoCatalogParser.parseProviders(result)
    }

    override suspend fun listRepositories(providerType: String): List<RepoCandidate> {
        val result = client.request(
            namespace.sourceProvidersListResources,
            buildJsonObject { put("providerType", providerType) },
        )
        return RepoCatalogParser.parseResources(result)
    }

    override suspend fun disconnect() {
        client.close()
        _connection.value = ConnectionState.Disconnected
    }
}

/**
 * Turns a JSON-RPC error into something the UI can say a useful sentence about.
 *
 * The mapping is by message text, which is unlovely but unavoidable: F-01
 * established there is no capability or entitlement probe, so a failed create is
 * the only place these conditions become visible.
 */
private fun AcpRemoteException.asDomainFailure(): Exception = when {
    isAuthFailure -> NotEntitledException(
        "This Kiro account cannot create cloud sessions. Cloud sessions need a Pro " +
            "plan or higher, and Identity Center organisations also need an admin " +
            "to enable the preview.",
    )
    error.message.contains("limit", ignoreCase = true) ||
        error.message.contains("concurrent", ignoreCase = true) ->
        SessionLimitReachedException(
            "You have reached the limit of concurrent cloud sessions. " +
                "Finish or delete one before starting another.",
        )
    else -> this
}

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Builds `params._meta.kiro`, the dispatch block every cloud call needs. */
private fun JsonObjectBuilder.putKiroMeta(
    build: JsonObjectBuilder.() -> Unit,
) {
    put("_meta", buildJsonObject { put("kiro", buildJsonObject(build)) })
}

private fun SessionUpdate.PendingInteraction.toPermissionRequest(): PermissionRequest =
    PermissionRequest(
        sessionId = sessionId,
        toolCallId = toolCallId,
        title = question,
        options = options,
        consent = PermissionRequest.Consent(
            capability = null,
            resource = question,
            askType = null,
            workspaceRoot = null,
        ),
        // No rpcId: this arrived as a notification, so the answer must go through
        // _kiro/permission/respond rather than as a JSON-RPC response.
        rpcId = null,
    )
