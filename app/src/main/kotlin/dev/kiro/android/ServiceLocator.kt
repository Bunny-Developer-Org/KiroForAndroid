package dev.kiro.android

import android.content.Context
import dev.kiro.android.platform.AndroidLogger
import dev.kiro.android.platform.CustomTabBrowserLauncher
import dev.kiro.android.platform.DataStoreBridgeRegistry
import dev.kiro.android.platform.DataStorePinnedSessionStore
import dev.kiro.android.platform.InMemoryDriftMetrics
import dev.kiro.android.platform.KeystoreTokenStore
import dev.kiro.android.platform.PinnedSessionStore
import dev.kiro.android.platform.WebSocketAcpTransport
import dev.kiro.core.acp.AcpClient
import dev.kiro.core.auth.BridgeRegistry
import dev.kiro.core.auth.TokenStore
import dev.kiro.core.session.BridgeGateway
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.FakeGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual constructor injection behind a small service locator.
 *
 * **This fixes the DI pattern for the project** — F-02 was required to choose one
 * and record it so ten parallel work items do not diverge. Hilt is permitted by
 * ADR-003 but buys little here: the graph is shallow, there is one long-lived
 * gateway, and every screen takes what it needs through its own constructor.
 * Revisit if the graph grows a second axis (per-session scopes, say); do not
 * revisit it screen by screen.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val logger by lazy { AndroidLogger() }
    val metrics by lazy { InMemoryDriftMetrics() }

    val tokenStore: TokenStore by lazy { KeystoreTokenStore(appContext) }
    val bridges: BridgeRegistry by lazy { DataStoreBridgeRegistry(appContext) }
    val pinnedSessions: PinnedSessionStore by lazy { DataStorePinnedSessionStore(appContext) }
    val browser by lazy { CustomTabBrowserLauncher(appContext) }

    private var activeGateway: CloudSessionGateway? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The gateway in use.
     *
     * Falls back to [FakeGateway] when nothing is paired, so every screen has
     * something real to render against — which is what lets the UI work items
     * proceed independently of the bridge, and what makes previews possible.
     */
    fun gateway(): CloudSessionGateway = activeGateway ?: FakeGateway()

    suspend fun connect(url: String, token: String): CloudSessionGateway {
        // MainActivity's reconnect loop calls this every 2s until it succeeds; without
        // closing what came before, each attempt leaks a socket and a pump coroutine
        // rather than replacing the last one.
        activeGateway?.disconnect()
        activeGateway = null
        val transport = WebSocketAcpTransport(url, token)
        val client = AcpClient(transport, scope, logger, metrics)
        val gateway = BridgeGateway(client, scope, logger, metrics)
        try {
            gateway.connect()
        } catch (e: Throwable) {
            gateway.disconnect()
            throw e
        }
        activeGateway = gateway
        return gateway
    }

    suspend fun disconnect() {
        activeGateway?.disconnect()
        activeGateway = null
    }
}
