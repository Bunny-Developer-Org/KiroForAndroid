package dev.kiro.android.platform

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.kiro.core.acp.AcpJson
import dev.kiro.core.auth.BridgeRegistry
import dev.kiro.core.auth.PairedBridge
import dev.kiro.core.util.DriftMetrics
import dev.kiro.core.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

private val Context.bridgeDataStore: DataStore<Preferences> by preferencesDataStore("kiro_bridges")
private val Context.pinnedSessionsDataStore: DataStore<Preferences> by preferencesDataStore("kiro_pinned_sessions")

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

/**
 * A single named string slot.
 *
 * [DataStoreBridgeRegistry] only ever needs to read and overwrite one JSON
 * blob, and every real [Preferences] instance is constructed through a Kotlin
 * `internal` API of the datastore library — unreachable from a plain JVM unit
 * test with no Robolectric or instrumentation. Depending on this narrow seam
 * instead of on [DataStore] directly means the round-trip logic can be pinned
 * against an in-memory fake with no Android or DataStore types involved at all.
 */
interface StringStore {
    suspend fun read(): String?
    suspend fun write(value: String)
}

/** The real [StringStore], backed by Jetpack DataStore Preferences. */
class DataStorePreferenceStringStore(
    private val store: DataStore<Preferences>,
    private val key: Preferences.Key<String>,
) : StringStore {
    override suspend fun read(): String? = store.data.first()[key]
    override suspend fun write(value: String) {
        store.edit { it[key] = value }
    }
}

/** Bridges are a list, not a single host — see [PairedBridge]. */
class DataStoreBridgeRegistry(private val stringStore: StringStore) : BridgeRegistry {

    constructor(context: Context) : this(
        DataStorePreferenceStringStore(context.bridgeDataStore, stringPreferencesKey("bridges")),
    )

    override suspend fun list(): List<PairedBridge> {
        val raw = stringStore.read() ?: return emptyList()
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
        stringStore.write(encoded)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * Which sessions the user has pinned.
 *
 * Purely a client convenience — F-10 confirmed there is no wire-level concept of
 * a pinned session, so it lives here rather than as a field on [dev.kiro.core.model.CloudSession].
 * An interface (rather than the DataStore implementation directly) is what lets
 * [dev.kiro.android.ui.sessions.SessionListViewModel] be tested with a plain
 * in-memory fake instead of a real DataStore.
 */
public interface PinnedSessionStore {
    public val pinnedIds: Flow<Set<String>>
    public suspend fun toggle(sessionId: String)
}

/** Same shape as [DataStoreBridgeRegistry]: one DataStore Preferences file, one key. */
class DataStorePinnedSessionStore(private val context: Context) : PinnedSessionStore {

    private val key = stringSetPreferencesKey("pinned_session_ids")

    override val pinnedIds: Flow<Set<String>> =
        context.pinnedSessionsDataStore.data.map { it[key] ?: emptySet() }

    override suspend fun toggle(sessionId: String) {
        context.pinnedSessionsDataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (sessionId in current) current - sessionId else current + sessionId
        }
    }
}
