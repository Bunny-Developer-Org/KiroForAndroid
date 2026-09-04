package dev.kiro.core.session

import dev.kiro.core.acp.AcpClient
import dev.kiro.core.acp.AcpMethods
import dev.kiro.core.acp.AcpRemoteException
import dev.kiro.core.acp.ConfigOptionParser
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
import dev.kiro.core.acp.modelSelection
import dev.kiro.core.auth.PairedBridge
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ConfigOption
import dev.kiro.core.model.ExecutionTarget
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.ModelSelection
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
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
    /**
     * How the bridge behind this gateway authenticates to Kiro, when the caller
     * knows.
     *
     * Used for one thing: an unauthorised cloud call has two plausible causes —
     * the account's plan, or the bridge's own credential — and the app has to name
     * both unless it can rule one out. See [asDomainFailure]. Defaults to
     * [PairedBridge.AuthMode.UNKNOWN] so nothing has to supply it; the message
     * simply stays more cautious when it is not supplied.
     */
    private val bridgeAuthMode: PairedBridge.AuthMode = PairedBridge.AuthMode.UNKNOWN,
    /**
     * Where the model catalogue survives a restart.
     *
     * See [ModelCatalogStore] for why it has to: the protocol offers no way to
     * list models without a session, so a create screen on a cold start has
     * nothing to show unless the last list was written down.
     */
    private val modelCatalogStore: ModelCatalogStore = ModelCatalogStore.None,
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

    private val _models = MutableStateFlow(ModelState())
    override val models: Flow<ModelState> = _models.asStateFlow()

    override fun modelsFor(sessionId: String): ModelSelection = _models.value.forSession(sessionId)

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
        seedCatalogFromStore()

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
        // The socket dying used to stop here. AcpClient's pump caught the failure,
        // logged one line and ended; nothing above it was told, so this gateway
        // went on reporting Connected over a dead connection and the app's
        // reconnect loop -- which waits on exactly this flow -- never woke up. The
        // first the user heard of it was the raw OS text of the next failed send:
        // "failed to send: Software caused connection abort".
        //
        // One-shot rather than a running collect: a client is not reusable, so the
        // first `false` is the last word this gateway has on the subject.
        scope.launch {
            client.live.first { !it }
            if (_connection.value != ConnectionState.Disconnected) {
                logger.warn("bridge connection dropped")
                _connection.value = ConnectionState.Disconnected
            }
        }

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
                            // The only channel a *cloud* session has for its model
                            // list: the sandbox pushes config options here rather
                            // than returning them from session/new or session/load
                            // (PROTOCOL-FINDINGS §4d). Recorded before the update is
                            // published so a collector that reacts to it already
                            // sees the new selection.
                            if (update is SessionUpdate.ConfigOptionsChanged) {
                                recordModels(update.sessionId, update.options.modelSelection())
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
            // local git — the repositories below are what the sandbox clones, not
            // this value. `cwd` used to be sent empty on that basis, but the live
            // server now rejects that: `session/new` answers `Invalid params: cwd
            // must be an absolute path` (PROTOCOL-FINDINGS §4c, verified 2026-09-02
            // against KAS 0.52.1). The server still never uses it for a cloud
            // session — `/`, `/tmp` and `/workspace` all created sessions
            // identically — so this is a placeholder to satisfy validation, not a
            // meaningful path.
            put("cwd", PLACEHOLDER_CWD)
            put("mcpServers", buildJsonArray { })
            // Kept because it has been sent since F-05 and the live server has
            // never objected, but it is not what selects the mode: KAS reads
            // `_meta.kiro.modeId` and nothing reads a top-level `agentMode`
            // (PROTOCOL-FINDINGS §4d). The meta field below is the load-bearing one.
            request.modeId?.let { put("agentMode", it) }
            putKiroMeta {
                // sessionSource and executionTarget are two spellings of one
                // decision and the agent rejects sending both. Send the target.
                put(
                    "executionTarget",
                    buildJsonObject { put("kind", ExecutionTarget.CLOUD_SANDBOX.wire) },
                )
                request.modeId?.let { put("modeId", it) }
                // Accepted by the schema and honoured on the local path; the cloud
                // create drops it (§4d), which is why applyRequestedModel below
                // does the real work. Sent anyway so the local path is right too.
                request.modelId?.let { put("modelId", it) }
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
            throw e.asDomainFailure(bridgeAuthMode)
        }

        val sessionId = (result as? JsonObject)?.let { it.strOrNull("sessionId") }
            ?: throw CloudUnavailableException("session/new returned no sessionId")

        // Present on a local session, absent on a cloud one. Recording it either
        // way keeps the "we were told nothing" case explicit rather than implied.
        recordModelsFromResult(sessionId, result)
        request.modelId?.let { applyRequestedModel(sessionId, it) }

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
        val result = client.request(
            AcpMethods.SESSION_LOAD,
            buildJsonObject {
                put("sessionId", sessionId)
                // Unlike session/new (see createSession above), session/load does not
                // enforce cwd as an absolute path — verified live 2026-09-02 against
                // the same KAS 0.52.1 server, loading a session immediately after
                // creating it. An empty cwd here is not the same bug; leave it.
                put("cwd", "")
                put("mcpServers", buildJsonArray { })
                putKiroMeta { put("sessionSource", source.wire) }
            },
            // Replay is large -- 991 updates on one real session -- and arrives
            // before this resolves.
            timeout = 5.minutes,
        )
        // A local session's load response carries configOptions; a cloud session's
        // deliberately does not, and its models arrive later on the downlink.
        recordModelsFromResult(sessionId, result)
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

    /**
     * Switches the session's model.
     *
     * Sent as `session/set_config_option`, not `session/set_model`. The latter is
     * the ACP-standard spelling and the one this client used to name, but KAS does
     * not implement the handler behind it and answers `-32601` — so the standard
     * call is the *fallback* here, tried only if the agent turns out not to know
     * the config-option verb either (PROTOCOL-FINDINGS §4d).
     *
     * The response carries the agent's authoritative config set, so the local
     * snapshot is updated from what came back rather than from what was asked for
     * — a rejected or coerced value must not be rendered as the current model.
     */
    override suspend fun setModel(sessionId: String, modelId: String) {
        val result = try {
            client.request(
                AcpMethods.SESSION_SET_CONFIG_OPTION,
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("configId", ConfigOption.MODEL)
                    put("value", modelId)
                },
            )
        } catch (e: AcpRemoteException) {
            if (e.error.code != RpcError.METHOD_NOT_FOUND) throw e
            logger.warn("agent has no session/set_config_option; falling back to session/set_model")
            client.request(
                AcpMethods.SESSION_SET_MODEL,
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("modelId", modelId)
                },
            )
        }
        recordModelsFromResult(sessionId, result)
    }

    /**
     * Records the model selection carried by a `session/new`, `session/load` or
     * `session/set_config_option` result. A result with no `configOptions` — every
     * cloud `session/new` and `session/load` — leaves the existing snapshot alone
     * rather than overwriting a known selection with an empty one.
     */
    private suspend fun recordModelsFromResult(sessionId: String, result: JsonElement?) {
        val selection = ConfigOptionParser.parse(result).modelSelection()
        if (selection.isKnown) recordModels(sessionId, selection)
    }

    private suspend fun recordModels(sessionId: String, selection: ModelSelection) {
        val before = _models.value.lastKnownCatalog
        _models.value = _models.value.let { state ->
            ModelState(
                bySession = state.bySession + (sessionId to selection),
                // Only ever replaced by a non-empty catalog: a session that reports
                // a current model without a list must not blank out the list a
                // create screen is relying on.
                lastKnownCatalog = selection.available.ifEmpty { state.lastKnownCatalog },
            )
        }
        val after = _models.value.lastKnownCatalog
        // Only on an actual change: `config_option_update` arrives repeatedly
        // through a session and re-writing an identical list on each one would
        // put a disk write on a notification path for nothing.
        if (after.isNotEmpty() && after != before) {
            runCatching { modelCatalogStore.write(after) }
                .onFailure { logger.warn("could not persist the model catalogue: ${it.message}") }
        }
    }

    /**
     * Loads the remembered catalogue, if this connection has not already learned
     * a better one.
     *
     * Fire-and-forget on [scope] rather than awaited in [connect]: a create screen
     * with no models yet is a working screen, and making the handshake wait on a
     * disk read would trade something that matters for something that does not.
     */
    private fun seedCatalogFromStore() {
        scope.launch {
            val remembered = runCatching { modelCatalogStore.read() }.getOrDefault(emptyList())
            if (remembered.isEmpty()) return@launch
            _models.value = _models.value.let { state ->
                if (state.lastKnownCatalog.isEmpty()) state.copy(lastKnownCatalog = remembered) else state
            }
        }
    }

    /**
     * Best-effort model switch on a freshly created session.
     *
     * Deliberately does not fail the create. The session exists at this point and
     * throwing would strand it — a cloud session the user cannot see is worse than
     * one running the default model, and the snapshot in [models] will keep saying
     * what the agent actually reports rather than what was requested.
     */
    private suspend fun applyRequestedModel(sessionId: String, modelId: String) {
        try {
            setModel(sessionId, modelId)
        } catch (e: Throwable) {
            logger.warn("session $sessionId created but model $modelId was not applied: ${e.message}")
        }
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
 * What `session/new` gets for `cwd` on a cloud session.
 *
 * Any absolute path satisfies the live server's validation identically — `/`,
 * `/tmp` and `/workspace` all created a session with no observable difference
 * (PROTOCOL-FINDINGS §4c). `/` is used because it makes no claim about a
 * filesystem this app never has: not a repo checkout, not a real workspace, just
 * the shortest string that is unambiguously an absolute path.
 */
private const val PLACEHOLDER_CWD: String = "/"

/**
 * Turns a JSON-RPC error into something the UI can say a useful sentence about.
 *
 * The mapping is by message text, which is unlovely but unavoidable: F-01
 * established there is no capability or entitlement probe, so a failed create is
 * the only place these conditions become visible.
 */
private fun AcpRemoteException.asDomainFailure(authMode: PairedBridge.AuthMode): Exception = when {
    isAuthFailure -> NotEntitledException(unauthorizedMessage(authMode, requestId))
    error.message.contains("limit", ignoreCase = true) ||
        error.message.contains("concurrent", ignoreCase = true) ->
        SessionLimitReachedException(
            "You have reached the limit of concurrent cloud sessions. " +
                "Finish or delete one before starting another.",
        )
    else -> this
}

/**
 * What the app is allowed to say when Kiro's service answers `UnauthorizedException`.
 *
 * **This message used to assert "this Kiro account cannot create cloud sessions …
 * needs a Pro plan or higher".** On 2026-09-03 that sentence was shown to the
 * owner of a Kiro **Pro+** account and cost real debugging time: the bridge had
 * been provisioned with a `KIRO_API_KEY`, and it was that identity the service
 * refused — `session/list`, `_kiro/sourceProviders/list` and `session/new` alike,
 * with `faultKind: serviceRejection`.
 *
 * The same day, a three-way ACP probe settled which limb it was
 * (PROTOCOL-FINDINGS §4b, correction dated 2026-09-03): with no `KIRO_API_KEY`
 * the identical probe listed 28 cloud sessions; with the existing key, and again
 * with a freshly minted key from the *same* Pro+ account, `initialize` succeeded
 * and `session/list` was rejected. `--auth-method cli` was passed in all three —
 * the environment variable was the only variable. So under `API_KEY` the auth
 * mode is named first, because on this account it is the observed cause twice
 * over; the plan stays in the sentence as the secondary possibility rather than
 * the headline, because it is exactly the claim that misled.
 *
 * The boundary the copy respects: both keys came from one account's console, so
 * nothing here licenses "API keys never reach cloud sessions" as a universal
 * statement — only that this is the first thing to check.
 */
private fun unauthorizedMessage(authMode: PairedBridge.AuthMode, requestId: String?): String {
    val cause = when (authMode) {
        PairedBridge.AuthMode.API_KEY ->
            "This bridge authenticates with a host API key rather than as you, and that is the " +
                "likeliest cause: on a Kiro Pro+ account, two separate API keys were both refused " +
                "for cloud sessions while an interactive `kiro-cli login` on the same account " +
                "worked seconds later. Re-run the bridge without KIRO_API_KEY, signed in with " +
                "`kiro-cli login`. If it still fails, the remaining possibility is entitlement — " +
                "cloud sessions need a Pro plan or higher, and Identity Center organisations also " +
                "need an admin to enable the preview."

        PairedBridge.AuthMode.CLI_LOGIN ->
            "This bridge is signed in as you rather than running under a host API key, so the " +
                "credential is the one you signed in with. The remaining explanation is entitlement: " +
                "cloud sessions need a Pro plan or higher, and Identity Center organisations also " +
                "need an admin to enable the preview."

        PairedBridge.AuthMode.UNKNOWN ->
            "Check how the bridge authenticates first. A bridge running under a KIRO_API_KEY has " +
                "been observed being refused for cloud sessions on a Pro+ account, with two " +
                "different keys, where an interactive `kiro-cli login` on that same account worked. " +
                "If the bridge is already signed in as you, the remaining explanation is " +
                "entitlement: cloud sessions need a Pro plan or higher, and Identity Center " +
                "organisations also need an admin to enable the preview."
    }
    val trace = requestId?.let { " Kiro's request id for this failure is $it." }.orEmpty()
    return "Kiro's service refused this request as unauthorized, so no cloud session was created. " +
        cause + trace
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
