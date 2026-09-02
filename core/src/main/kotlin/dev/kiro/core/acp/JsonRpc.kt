package dev.kiro.core.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 framing for ACP.
 *
 * Deliberately structural rather than typed: `params` and `result` stay as
 * [JsonElement] and are decoded by the layer that knows what it asked for. That
 * is what lets an unrecognised frame be logged and dropped instead of taking the
 * session down with it — the requirement ADR-003 §3 calls load-bearing, and that
 * F-01 showed is not theoretical: a large set of `_kiro/…` notifications is
 * undocumented and open-ended.
 */

/** The `Json` every layer of this module must use. The settings are not optional. */
public val AcpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    isLenient = false
    coerceInputValues = true
}

/**
 * A JSON-RPC id. The spec allows a string, a number, or null; Kiro sends numbers
 * and we send numbers, but a client that assumes that is one peer away from a bug.
 */
public sealed interface RpcId {
    public data class Num(val value: Long) : RpcId
    public data class Text(val value: String) : RpcId

    public fun toJson(): JsonPrimitive = when (this) {
        is Num -> JsonPrimitive(value)
        is Text -> JsonPrimitive(value)
    }

    public companion object {
        public fun fromJson(element: JsonElement?): RpcId? {
            val primitive = (element as? JsonPrimitive) ?: return null
            if (primitive is JsonNull) return null
            primitive.longOrNullCompat()?.let { return Num(it) }
            return if (primitive.isString) Text(primitive.content) else null
        }

        private fun JsonPrimitive.longOrNullCompat(): Long? =
            if (isString) null else content.toLongOrNull()
    }
}

public sealed interface RpcMessage

/** Peer → us, or us → peer, expecting a response with a matching [id]. */
public data class RpcRequest(
    val id: RpcId,
    val method: String,
    val params: JsonElement? = null,
) : RpcMessage

/** No id. Fire and forget, in either direction. */
public data class RpcNotification(
    val method: String,
    val params: JsonElement? = null,
) : RpcMessage

public data class RpcResponse(
    val id: RpcId,
    val result: JsonElement? = null,
    val error: RpcError? = null,
) : RpcMessage {
    val isError: Boolean get() = error != null
}

public data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    public companion object {
        public const val PARSE_ERROR: Int = -32700
        public const val INVALID_REQUEST: Int = -32600
        public const val METHOD_NOT_FOUND: Int = -32601
        public const val INVALID_PARAMS: Int = -32602
        public const val INTERNAL_ERROR: Int = -32603
    }
}

/**
 * A frame that arrived but could not be understood. Returning this rather than
 * throwing is the whole point: the caller counts it (F-22 tracks parse-failure
 * rate as the trigger for revisiting ADR-002) and carries on.
 */
public data class RpcMalformed(
    val raw: String,
    val reason: String,
) : RpcMessage

public object JsonRpcCodec {

    private const val VERSION = "2.0"

    public fun encode(message: RpcMessage): String = when (message) {
        is RpcRequest -> buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", message.id.toJson())
            put("method", message.method)
            message.params?.let { put("params", it) }
        }
        is RpcNotification -> buildJsonObject {
            put("jsonrpc", VERSION)
            put("method", message.method)
            message.params?.let { put("params", it) }
        }
        is RpcResponse -> buildJsonObject {
            put("jsonrpc", VERSION)
            put("id", message.id.toJson())
            if (message.error != null) {
                put(
                    "error",
                    buildJsonObject {
                        put("code", message.error.code)
                        put("message", message.error.message)
                        message.error.data?.let { put("data", it) }
                    },
                )
            } else {
                put("result", message.result ?: JsonNull)
            }
        }
        is RpcMalformed -> error("A malformed frame is an input, never an output.")
    }.toString()

    /**
     * Never throws. A frame this cannot classify comes back as [RpcMalformed].
     */
    public fun decode(line: String): RpcMessage {
        val root = runCatching { AcpJson.parseToJsonElement(line) }.getOrNull()
            ?: return RpcMalformed(line, "not valid JSON")
        val obj = root as? JsonObject ?: return RpcMalformed(line, "not a JSON object")

        val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = RpcId.fromJson(obj["id"])

        return when {
            method != null && id != null -> RpcRequest(id, method, obj["params"])
            method != null -> RpcNotification(method, obj["params"])
            id != null -> RpcResponse(id, obj["result"], obj["error"]?.let(::decodeError))
            // A response whose id failed to parse is unroutable. Say so rather than
            // silently dropping a reply someone is awaiting.
            obj.containsKey("result") || obj.containsKey("error") ->
                RpcMalformed(line, "response with missing or unusable id")
            else -> RpcMalformed(line, "neither request, notification nor response")
        }
    }

    private fun decodeError(element: JsonElement): RpcError {
        val obj = element as? JsonObject ?: return RpcError(
            RpcError.INTERNAL_ERROR,
            "malformed error object",
            element,
        )
        val code = (obj["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: RpcError.INTERNAL_ERROR
        val message = (obj["message"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?: "unspecified error"
        return RpcError(code, message, obj["data"])
    }
}

/** `params._meta.kiro`, which is where Kiro puts nearly everything that matters. */
public fun JsonElement?.kiroMeta(): JsonObject? =
    (this as? JsonObject)?.get("_meta")?.let { it as? JsonObject }?.get("kiro") as? JsonObject

public fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

public fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

public fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

public fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
