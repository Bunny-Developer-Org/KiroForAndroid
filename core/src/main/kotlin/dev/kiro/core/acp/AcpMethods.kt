package dev.kiro.core.acp

/**
 * Method names.
 *
 * Core ACP methods are stable enough to be constants. Kiro's extensions are not:
 * the namespace is server-configurable (`KIRO_EXTENSION_NAMESPACE`) and the docs
 * disagree with themselves about its spelling, so [ExtensionNamespace] derives it
 * from the handshake rather than matching a constant. See PROTOCOL-FINDINGS A3.
 */
public object AcpMethods {
    public const val INITIALIZE: String = "initialize"
    public const val SESSION_NEW: String = "session/new"
    public const val SESSION_LOAD: String = "session/load"
    public const val SESSION_LIST: String = "session/list"
    public const val SESSION_PROMPT: String = "session/prompt"
    public const val SESSION_CANCEL: String = "session/cancel"
    public const val SESSION_SET_MODE: String = "session/set_mode"
    public const val SESSION_SET_MODEL: String = "session/set_model"

    /** Agent → client. The one server-initiated request the client must answer. */
    public const val SESSION_REQUEST_PERMISSION: String = "session/request_permission"

    /** Agent → client notification carrying every streaming update. */
    public const val SESSION_UPDATE: String = "session/update"
}

/**
 * The extension namespace, discovered rather than assumed.
 *
 * `kiro-cli 2.19.2` sends `_kiro/`, the ACP docs page says `_kiro.dev/`, and KAS
 * lets an operator change it. So: read the prefix off any extension method the
 * handshake enumerates, and build every extension call from it.
 */
public class ExtensionNamespace private constructor(public val prefix: String) {

    public fun method(suffix: String): String = prefix + suffix

    // The extension methods this client actually uses. Everything else the agent
    // advertises is deliberately ignored.
    public val sourceProvidersList: String get() = method("sourceProviders/list")
    public val sourceProvidersListResources: String get() = method("sourceProviders/listResources")
    public val permissionRespond: String get() = method("permission/respond")
    public val userInputRespond: String get() = method("userInput/respond")
    public val sessionDelete: String get() = method("session/delete")

    /** Agent → client notification names, for matching inbound frames. */
    public val sessionsChanged: String get() = method("sessions/changed")
    public val mcpStatus: String get() = method("mcp/status")
    public val userInput: String get() = method("userInput")

    public companion object {
        /** What 2.19.2 sends. Used only when the handshake enumerates nothing. */
        public const val DEFAULT_PREFIX: String = "_kiro/"

        public val Default: ExtensionNamespace = ExtensionNamespace(DEFAULT_PREFIX)

        /**
         * Derives the prefix from the handshake's `extensionMethods` array by taking
         * the longest common prefix that ends in `/`. Falls back to [DEFAULT_PREFIX]
         * when the array is absent or too irregular to read — an agent that
         * advertises nothing is one we simply make no extension calls to, and the
         * fallback keeps that path from needing a null check everywhere.
         */
        public fun from(extensionMethods: List<String>): ExtensionNamespace {
            if (extensionMethods.isEmpty()) return Default
            var candidate = extensionMethods.first()
            for (method in extensionMethods.drop(1)) {
                candidate = candidate.commonPrefixWith(method)
                if (candidate.isEmpty()) return Default
            }
            val cut = candidate.lastIndexOf('/')
            if (cut < 0) return Default
            return ExtensionNamespace(candidate.substring(0, cut + 1))
        }
    }

    override fun toString(): String = "ExtensionNamespace($prefix)"

    override fun equals(other: Any?): Boolean = other is ExtensionNamespace && other.prefix == prefix

    override fun hashCode(): Int = prefix.hashCode()
}
