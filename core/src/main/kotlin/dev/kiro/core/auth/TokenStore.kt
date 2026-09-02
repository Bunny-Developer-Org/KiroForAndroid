package dev.kiro.core.auth

/**
 * Where the app keeps its bridge device tokens.
 *
 * An interface in `core/` with its implementation in `app/` — the pattern
 * ADR-003 §2 prescribes for anything platform-specific, and the reason `core/`
 * stays testable on the JVM.
 *
 * These are **Auth-1** credentials: they authorise this phone to talk to a
 * bridge. No Kiro credential ever passes through here, or through the app at all.
 */
public interface TokenStore {
    public suspend fun put(bridgeId: String, token: String)
    public suspend fun get(bridgeId: String): String?
    public suspend fun remove(bridgeId: String)
    public suspend fun clear()
}

/**
 * A paired bridge.
 *
 * The app stores a *list* rather than a single bridge, because sessions live in
 * the Kiro account rather than on any one host: two bridges signed in as the same
 * account see the same sessions, so a user can run one on a Pi and one on a
 * laptop and lose no work if either goes away (ADR-005 §5.2).
 */
public data class PairedBridge(
    val id: String,
    val displayName: String,
    val url: String,
    val lastSeenMillis: Long?,
    /**
     * Whether that host authenticates as the user or under its own API key.
     *
     * "Signed in as you" and "running under a host key" are different trust
     * statements and settings must not blur them (AUTHENTICATION §3b).
     */
    val authMode: AuthMode,
) {
    public enum class AuthMode { CLI_LOGIN, API_KEY, UNKNOWN }
}

public interface BridgeRegistry {
    public suspend fun list(): List<PairedBridge>
    public suspend fun add(bridge: PairedBridge)
    public suspend fun remove(bridgeId: String)
    public suspend fun touch(bridgeId: String, lastSeenMillis: Long)
}
