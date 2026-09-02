package dev.kiro.android.platform

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.kiro.core.acp.AcpJson
import dev.kiro.core.auth.BridgeRegistry
import dev.kiro.core.auth.PairedBridge
import dev.kiro.core.util.DriftMetrics
import dev.kiro.core.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

private val Context.bridgeDataStore: DataStore<Preferences> by preferencesDataStore("kiro_bridges")

class AndroidLogger(private val tag: String = "Kiro") : Logger {
    override fun debug(message: String) { Log.d(tag, message) }
    override fun info(message: String) { Log.i(tag, message) }
    override fun warn(message: String, error: Throwable?) { Log.w(tag, message, error) }
    override fun error(message: String, error: Throwable?) { Log.e(tag, message, error) }
}

/**
 * Opens a URL in a **Custom Tab**, never a WebView.
 *
 * RFC 8252 §8.12 is explicit about this, and it is not negotiable anywhere in
 * this app: an embedded WebView lets the host app observe the user's credentials,
 * which is the entire thing the device-flow relay exists to avoid.
 */
class CustomTabBrowserLauncher(private val context: Context) {
    fun open(url: String) {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .also { it.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            .launchUrl(context, Uri.parse(url))
    }
}

/**
 * Counts protocol drift.
 *
 * ADR-002 §5 makes the unknown-method and parse-failure rate the explicit trigger
 * for revisiting the runtime decision — which only works if it is measured rather
 * than assumed. Counts only; never the payload, never prompt or repository
 * content (F-22).
 */
class InMemoryDriftMetrics : DriftMetrics {
    private val parseFailures = AtomicLong()
    private val unknownMethods = AtomicLong()
    private val unknownKinds = AtomicLong()
    private val seenMethods = java.util.concurrent.ConcurrentHashMap<String, Long>()

    override fun parseFailure(reason: String) { parseFailures.incrementAndGet() }

    override fun unknownMethod(method: String) {
        unknownMethods.incrementAndGet()
        seenMethods.merge(method, 1L, Long::plus)
    }

    override fun unknownUpdateKind(kind: String) {
        unknownKinds.incrementAndGet()
        seenMethods.merge("update:$kind", 1L, Long::plus)
    }

    fun snapshot(): Snapshot = Snapshot(
        parseFailures = parseFailures.get(),
        unknownMethods = unknownMethods.get(),
        unknownUpdateKinds = unknownKinds.get(),
        byName = seenMethods.toMap(),
    )

    data class Snapshot(
        val parseFailures: Long,
        val unknownMethods: Long,
        val unknownUpdateKinds: Long,
        val byName: Map<String, Long>,
    )
}

/** Bridges are a list, not a single host — see [PairedBridge]. */
class DataStoreBridgeRegistry(private val context: Context) : BridgeRegistry {

    private val key = stringPreferencesKey("bridges")

    override suspend fun list(): List<PairedBridge> {
        val raw = context.bridgeDataStore.data.first()[key] ?: return emptyList()
        val array = runCatching { AcpJson.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            PairedBridge(
                id = obj.string("id") ?: return@mapNotNull null,
                displayName = obj.string("displayName") ?: "Bridge",
                url = obj.string("url") ?: return@mapNotNull null,
                lastSeenMillis = obj.string("lastSeenMillis")?.toLongOrNull(),
                authMode = runCatching {
                    PairedBridge.AuthMode.valueOf(obj.string("authMode") ?: "UNKNOWN")
                }.getOrDefault(PairedBridge.AuthMode.UNKNOWN),
            )
        }
    }

    override suspend fun add(bridge: PairedBridge) {
        write(list().filterNot { it.id == bridge.id } + bridge)
    }

    override suspend fun remove(bridgeId: String) {
        write(list().filterNot { it.id == bridgeId })
    }

    override suspend fun touch(bridgeId: String, lastSeenMillis: Long) {
        write(list().map { if (it.id == bridgeId) it.copy(lastSeenMillis = lastSeenMillis) else it })
    }

    private suspend fun write(bridges: List<PairedBridge>) {
        val encoded = buildJsonArray {
            bridges.forEach { bridge ->
                add(
                    buildJsonObject {
                        put("id", bridge.id)
                        put("displayName", bridge.displayName)
                        put("url", bridge.url)
                        bridge.lastSeenMillis?.let { put("lastSeenMillis", it.toString()) }
                        put("authMode", bridge.authMode.name)
                    },
                )
            }
        }.toString()
        context.bridgeDataStore.edit { it[key] = encoded }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
