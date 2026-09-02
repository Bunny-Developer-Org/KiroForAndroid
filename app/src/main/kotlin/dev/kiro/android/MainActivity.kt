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
import dev.kiro.android.ui.onboarding.PairingScreen
import dev.kiro.android.ui.AppNavigation
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.core.auth.PairedBridge
import dev.kiro.core.session.ConnectionState
import kotlinx.coroutines.launch

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
    var pairingError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        paired = ServiceLocator.bridges.list().firstOrNull()
        paired?.let { bridge ->
            val token = ServiceLocator.tokenStore.get(bridge.id)
            if (token != null) {
                runCatching { ServiceLocator.connect(bridge.url, token) }
                    .onSuccess { _ ->
                        connection = ConnectionState.Connected(
                            agentSupportsCloudSessions = true,
                            supportsImages = true,
                        )
                    }
                    .onFailure {
                        // A named state, not a spinner: the bridge is very often
                        // simply a machine that is asleep.
                        connection = ConnectionState.Unreachable(
                            lastSeenMillis = bridge.lastSeenMillis,
                            onlyBridgeIsWorkstation = true,
                        )
                    }
            }
        }
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
        AppNavigation(gateway = ServiceLocator.gateway(), connection = connection)
    }
}
