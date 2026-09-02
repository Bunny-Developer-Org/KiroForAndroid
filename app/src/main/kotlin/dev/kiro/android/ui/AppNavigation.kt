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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kiro.android.ServiceLocator
import dev.kiro.android.service.SessionConnectionService
import dev.kiro.android.ui.create.CreateSessionScreen
import dev.kiro.android.ui.create.CreateSessionViewModel
import dev.kiro.android.ui.sessions.SessionListScreen
import dev.kiro.android.ui.sessions.SessionListViewModel
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.transcript.ApprovalCard
import dev.kiro.android.ui.transcript.Composer
import dev.kiro.android.ui.transcript.TranscriptHeader
import dev.kiro.android.ui.transcript.TranscriptScreen
import dev.kiro.android.ui.transcript.TranscriptViewModel
import dev.kiro.android.ui.transcript.UserInputCard
import dev.kiro.core.model.AgentMode
import dev.kiro.core.model.CloudSession
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.ConnectionState
import dev.kiro.core.session.GatewayRepoCatalog

@Composable
fun AppNavigation(
    gateway: CloudSessionGateway,
    connection: ConnectionState,
    onManageBridges: () -> Unit = {},
) {
    var screen by remember { mutableStateOf<Screen>(Screen.Sessions) }
    // Keyed on the gateway instance (not remembered across a bridge swap) so a
    // fallback to FakeGateway after a disconnect starts its own roster rather
    // than replaying a dead one's state -- the same key AppRoot already uses
    // when it decides whether to reconnect.
    val sessionListViewModel = remember(gateway) {
        SessionListViewModel(gateway, ServiceLocator.pinnedSessions)
    }
    val sessions by sessionListViewModel.sessions.collectAsState()
    val pinnedIds by sessionListViewModel.pinnedIds.collectAsState()

    when (val current = screen) {
        Screen.Sessions -> SessionListScreen(
            sessions = sessions,
            pinnedIds = pinnedIds,
            connection = connection,
            onOpen = { screen = Screen.Transcript(it) },
            onNewSession = { screen = Screen.Create() },
            onDelete = { sessionListViewModel.delete(it.id) },
            onTogglePin = { sessionListViewModel.togglePin(it.id) },
            onManageBridges = onManageBridges,
        )

        is Screen.Create -> CreateScreenHost(gateway, current.prefillRepos) { created ->
            sessionListViewModel.addCreated(created)
            screen = Screen.Transcript(created)
        }

        is Screen.Transcript -> TranscriptHost(
            gateway = gateway,
            session = current.session,
            supportsImages = (connection as? ConnectionState.Connected)?.supportsImages ?: false,
            onBack = { screen = Screen.Sessions },
            onNewSessionInRepo = {
                screen = Screen.Create(current.session.repositories.map { it.name })
            },
        )
    }
}

@Composable
private fun TranscriptHost(
    gateway: CloudSessionGateway,
    session: CloudSession,
    supportsImages: Boolean,
    onBack: () -> Unit,
    onNewSessionInRepo: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = remember(session.id) { TranscriptViewModel(gateway, session.id) }
    val state by viewModel.state.collectAsState()
    val permission by viewModel.permission.collectAsState()
    val userInput by viewModel.userInput.collectAsState()

    LaunchedEffect(session.id) {
        // The foreground service is what keeps the socket alive across a screen
        // lock. Starting it on attach rather than on first chunk means a turn that
        // begins while the phone is in a pocket is still watched.
        SessionConnectionService.start(context, session.id)
        runCatching { gateway.loadSession(session.id) }
    }

    Column(Modifier.fillMaxSize()) {
        TranscriptHeader(session = session, onBack = onBack, onNewSessionInRepo = onNewSessionInRepo)

        Box(Modifier.weight(1f)) {
            TranscriptScreen(state, Modifier.fillMaxSize())
        }

        // Above the composer, never below the fold: an approval or question the
        // user cannot see is an agent that is blocked for no reason. The two
        // channels are not mutually exclusive -- an agent can raise a userInput
        // question in one turn and a permission request in the next before either
        // is answered -- so both render, stacked, rather than one silently
        // dropping the other.
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

        userInput?.let { request ->
            UserInputCard(
                request = request,
                onSubmit = viewModel::respondToUserInput,
                onDismiss = { viewModel.respondToUserInput(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KiroLayout.ScreenGutter, vertical = 8.dp),
            )
        }

        Composer(
            onSend = viewModel::send,
            onCancel = viewModel::cancel,
            turnActive = state.isTurnActive,
            supportsImages = supportsImages,
        )
    }
}

@Composable
private fun CreateScreenHost(
    gateway: CloudSessionGateway,
    prefillRepos: List<String>,
    onCreated: (CloudSession) -> Unit,
) {
    val viewModel = remember(gateway, prefillRepos) {
        CreateSessionViewModel(
            gateway,
            GatewayRepoCatalog(gateway, ServiceLocator.recentRepos, ServiceLocator.logger),
            prefillRepos,
        )
    }
    val state by viewModel.state.collectAsState()

    CreateSessionScreen(
        state = state,
        modes = DEFAULT_MODES,
        onConnectProvider = {
            // Kiro owns provider connection; we send the user there in a Custom
            // Tab rather than pretending to do it here.
            ServiceLocator.browser.open("https://app.kiro.dev/settings")
        },
        onSetQuery = viewModel::setQuery,
        onSetManualEntry = viewModel::setManualEntry,
        onAddManual = viewModel::addManual,
        onToggle = viewModel::toggle,
        onRemove = viewModel::remove,
        onRetryCatalog = viewModel::retryCatalog,
        onCreate = { prompt, modeId -> viewModel.create(prompt, modeId, onCreated) },
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
