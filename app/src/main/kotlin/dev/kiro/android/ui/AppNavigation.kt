package dev.kiro.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kiro.android.ServiceLocator
import dev.kiro.android.service.SessionConnectionService
import dev.kiro.android.ui.create.CreateSessionScreen
import dev.kiro.android.ui.sessions.SessionListScreen
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.transcript.ApprovalCard
import dev.kiro.android.ui.transcript.Composer
import dev.kiro.android.ui.transcript.TranscriptScreen
import dev.kiro.android.ui.transcript.TranscriptViewModel
import dev.kiro.core.model.AgentMode
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.ConnectionState
import dev.kiro.core.session.CreateSessionRequest
import dev.kiro.core.session.NotEntitledException
import dev.kiro.core.session.SessionLimitReachedException
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(
    gateway: CloudSessionGateway,
    connection: ConnectionState,
    onManageBridges: () -> Unit = {},
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Sessions) }
    var sessions by remember { mutableStateOf<List<CloudSession>>(emptyList()) }

    LaunchedEffect(gateway, connection) {
        sessions = runCatching { gateway.listSessions() }.getOrDefault(emptyList())
    }

    // The roster pushes itself, so the list stays live without polling.
    LaunchedEffect(gateway) {
        gateway.rosterChanges.collect { change ->
            sessions = (change.upserted + sessions)
                .distinctBy { it.id }
                .filterNot { it.id in change.deleted }
        }
    }

    when (val current = screen) {
        Screen.Sessions -> SessionListScreen(
            sessions = sessions,
            connection = connection,
            onOpen = { screen = Screen.Transcript(it) },
            onNewSession = { screen = Screen.Create },
            onManageBridges = onManageBridges,
        )

        Screen.Create -> CreateScreenHost(gateway) { created ->
            sessions = listOf(created) + sessions
            screen = Screen.Transcript(created)
        }

        is Screen.Transcript -> TranscriptHost(gateway, current.session) {
            screen = Screen.Sessions
        }
    }
}

@Composable
private fun TranscriptHost(
    gateway: CloudSessionGateway,
    session: CloudSession,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember(session.id) { TranscriptViewModel(gateway, session.id) }
    val state by viewModel.state.collectAsState()
    val permission by viewModel.permission.collectAsState()

    LaunchedEffect(session.id) {
        // The foreground service is what keeps the socket alive across a screen
        // lock. Starting it on attach rather than on first chunk means a turn that
        // begins while the phone is in a pocket is still watched.
        SessionConnectionService.start(context, session.id)
        runCatching { gateway.loadSession(session.id) }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            TranscriptScreen(state, Modifier.fillMaxSize())
        }

        // Above the composer, never below the fold: an approval the user cannot
        // see is an agent that is blocked for no reason.
        permission?.let { request ->
            ApprovalCard(
                approval = dev.kiro.core.session.TranscriptReducer.PendingApproval(
                    toolCallId = request.toolCallId,
                    question = request.summary,
                    options = request.options,
                ),
                onRespond = viewModel::respondToPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KiroLayout.ScreenGutter, vertical = 8.dp),
            )
        }

        Composer(
            onSend = viewModel::send,
            onCancel = viewModel::cancel,
            turnActive = state.isTurnActive,
        )
    }
}

@Composable
private fun CreateScreenHost(
    gateway: CloudSessionGateway,
    onCreated: (CloudSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<SourceProvider>>(emptyList()) }
    var repositories by remember { mutableStateOf<List<RepoCandidate>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(gateway) {
        providers = runCatching { gateway.listSourceProviders() }.getOrDefault(emptyList())
        repositories = providers
            .filter { it.connectionStatus == SourceProvider.ConnectionStatus.CONNECTED }
            .flatMap { provider ->
                runCatching { gateway.listRepositories(provider.providerType) }
                    .getOrDefault(emptyList())
            }
    }

    CreateSessionScreen(
        providers = providers,
        repositories = repositories,
        modes = DEFAULT_MODES,
        busy = busy,
        errorMessage = error,
        onConnectProvider = {
            // Kiro owns provider connection; we send the user there in a Custom
            // Tab rather than pretending to do it here.
            ServiceLocator.browser.open("https://app.kiro.dev/settings")
        },
        onCreate = { repos, prompt, mode ->
            busy = true
            error = null
            scope.launch {
                runCatching {
                    gateway.createSession(CreateSessionRequest(repos, prompt, mode))
                }.onSuccess { created ->
                    gateway.prompt(
                        created.id,
                        listOf(dev.kiro.core.session.PromptBlock.Text(prompt)),
                    )
                    onCreated(created)
                }.onFailure { failure ->
                    // Each of these has a different thing the user can do about it,
                    // so each gets its own sentence rather than a generic failure.
                    error = when (failure) {
                        is NotEntitledException -> failure.message
                        is SessionLimitReachedException -> failure.message
                        else -> "Could not start the session: ${failure.message}"
                    }
                }
                busy = false
            }
        },
    )
}

/**
 * Fallback modes.
 *
 * The real list arrives on the `config_option_update` channel once a session
 * exists — but the create screen needs choices *before* there is a session to ask.
 * These are the modes 2.19.2 offers; if the agent later reports a different set,
 * that set wins.
 */
private val DEFAULT_MODES = listOf(
    AgentMode("vibe", "Default", "General coding assistance"),
    AgentMode("spec", "Spec", "Structured feature development"),
    AgentMode("plan", "Plan", "Plan before acting"),
    AgentMode("autonomous", "Autonomous", "Works without asking"),
)
