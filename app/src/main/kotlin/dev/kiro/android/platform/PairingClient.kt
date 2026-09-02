package dev.kiro.android.platform

import dev.kiro.core.acp.AcpJson
import dev.kiro.core.auth.PairedBridge
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Redeems a pairing code for a device token.
 *
 * The errors are deliberately distinct rather than a single "pairing failed":
 * F-07 requires each failure to say what to do next, and "wrong code", "too late"
 * and "slow down" have three different next steps.
 */
class PairingClient(private val client: HttpClient = HttpClient()) {

    sealed interface Result {
        data class Paired(val token: String, val authMode: PairedBridge.AuthMode) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun pair(bridgeUrl: String, code: String, deviceName: String): Result {
        val httpUrl = bridgeUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .removeSuffix("/acp")
            .trimEnd('/') + "/pair"

        return try {
            val response = client.post(httpUrl) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("code", code)
                        put("deviceName", deviceName)
                    }.toString(),
                )
            }
            val body = runCatching {
                AcpJson.parseToJsonElement(response.bodyAsText()) as? JsonObject
            }.getOrNull()

            when (response.status) {
                HttpStatusCode.OK -> {
                    val token = body?.string("token")
                        ?: return Result.Failed("The bridge paired but sent no token.")
                    Result.Paired(token, body.string("authMode").toAuthMode())
                }
                else -> Result.Failed(
                    body?.string("error") ?: "The bridge refused the pairing request.",
                )
            }
        } catch (e: Exception) {
            // Reachability is the common failure here, and it is usually the
            // bridge being off rather than anything the user typed wrong.
            Result.Failed(
                "Could not reach the bridge at $bridgeUrl. Check the address, and " +
                    "check the machine running it is awake. (${e.message})",
            )
        }
    }

    private fun String?.toAuthMode(): PairedBridge.AuthMode = when (this) {
        "api_key" -> PairedBridge.AuthMode.API_KEY
        "cli_login" -> PairedBridge.AuthMode.CLI_LOGIN
        else -> PairedBridge.AuthMode.UNKNOWN
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
