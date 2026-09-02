package dev.kiro.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.kiro.android.platform.PairingClient
import dev.kiro.android.service.Backoff
import dev.kiro.android.ui.onboarding.PairingScreen
import dev.kiro.android.ui.AppNavigation
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.core.auth.PairedBridge
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.ConnectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge, then let each surface own its insets. Safe area is padding,
        // not size -- that is what keeps the composer's send button above the
        // gesture bar (VISUAL-LANGUAGE §6).
        enableEdgeToEdge()
        setContent {
            KiroTheme {
                Scaffold { innerPadding ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .safeDrawingPadding(),
                    ) {
                        AppRoot()
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf<PairedBridge?>(null) }
    var connection by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    var gateway by remember { mutableStateOf<CloudSessionGateway>(ServiceLocator.gateway()) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        paired = ServiceLocator.bridges.list().firstOrNull()
    }

    LaunchedEffect(paired) {
        val bridge = paired ?: return@LaunchedEffect
        val token = ServiceLocator.tokenStore.get(bridge.id) ?: return@LaunchedEffect
        reconnectLoop(bridge, token, setGateway = { gateway = it }, setConnection = { connection = it })
    }

    if (paired == null) {
        PairingScreen(
            onPair = { url, code ->
                busy = true
                pairingError = null
                scope.launch {
                    when (val result = PairingClient().pair(url, code, android.os.Build.MODEL)) {
                        is PairingClient.Result.Paired -> {
                            val bridge = PairedBridge(
                                id = url,
                                displayName = url.substringAfter("://").substringBefore(':'),
                                url = url,
                                lastSeenMillis = System.currentTimeMillis(),
                                authMode = result.authMode,
                            )
                            ServiceLocator.tokenStore.put(bridge.id, result.token)
                            ServiceLocator.bridges.add(bridge)
                            paired = bridge
                        }
                        is PairingClient.Result.Failed -> pairingError = result.message
                    }
                    busy = false
                }
            },
            errorMessage = pairingError,
            busy = busy,
        )
    } else {
        // Everything past pairing is one graph, so the transcript can be handed the
        // session object it already has rather than an id to look up again.
        AppNavigation(gateway = gateway, connection = connection)
    }
}

/**
 * Connects, streams until the socket drops, then backs off and retries --
 * forever, since a paired bridge is expected to come and go with the
 * workstation's own sleep/wake cycle.
 *
 * One [Backoff] for the whole loop, not one per attempt: the attempt count and
 * delay only mean anything if they accumulate across a losing streak, and
 * [Backoff.reset] is what lets a network-regained event erase that streak.
 */
private suspend fun reconnectLoop(
    bridge: PairedBridge,
    token: String,
    setGateway: (CloudSessionGateway) -> Unit,
    setConnection: (ConnectionState) -> Unit,
) {
    val backoff = Backoff()
    while (true) {
        runCatching { ServiceLocator.connect(bridge.url, token) }
            .onSuccess { activeGtw ->
                backoff.reset()
                setGateway(activeGtw)
                setConnection(
                    ConnectionState.Connected(agentSupportsCloudSessions = true, supportsImages = true),
                )
                try {
                    activeGtw.connection.collect { conn ->
                        setConnection(conn)
                        if (conn !is ConnectionState.Connected) {
                            setGateway(ServiceLocator.gateway())
                        }
                    }
                } catch (e: Throwable) {
                    ServiceLocator.logger.warn("connection dropped: ${e.message}")
                    setConnection(
                        ConnectionState.Unreachable(
                            lastSeenMillis = bridge.lastSeenMillis,
                            onlyBridgeIsWorkstation = true,
                        ),
                    )
                    setGateway(ServiceLocator.gateway())
                }
            }
            .onFailure { e ->
                ServiceLocator.logger.warn("connect failed: ${e.message}")
                setConnection(
                    ConnectionState.Unreachable(
                        lastSeenMillis = bridge.lastSeenMillis,
                        onlyBridgeIsWorkstation = true,
                    ),
                )
                setGateway(ServiceLocator.gateway())
            }

        val waitMillis = backoff.nextDelayMillis()
        setConnection(ConnectionState.Reconnecting(attempt = backoff.attempts, nextRetryMillis = waitMillis))
        awaitRetryOrConnectivity(waitMillis, backoff)
    }
}

/**
 * Races the backoff wait against connectivity coming back, so a phone that
 * regains a network 45s into a 60s wait does not sit out the rest of it
 * before trying again.
 */
private suspend fun awaitRetryOrConnectivity(waitMillis: Long, backoff: Backoff) {
    coroutineScope {
        val waitJob = async { delay(waitMillis) }
        val connectivityJob = async { ServiceLocator.connectivityObserver.onConnectivityRegained.first() }
        select<Unit> {
            waitJob.onAwait { }
            connectivityJob.onAwait { backoff.reset() }
        }
        waitJob.cancel()
        connectivityJob.cancel()
    }
}
