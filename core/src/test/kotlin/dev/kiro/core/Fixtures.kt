package dev.kiro.core

import dev.kiro.core.acp.AcpJson
import dev.kiro.core.acp.JsonRpcCodec
import dev.kiro.core.acp.RpcMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Loads the golden fixtures F-01 captured from a real `kiro-cli 2.19.2`.
 *
 * These are the regression tests against protocol drift. When Kiro changes
 * something, a test here fails and names what moved — which is the whole reason
 * the spike committed frames rather than prose.
 */
object Fixtures {

    data class Frame(val direction: String, val raw: String) {
        val decoded: RpcMessage get() = JsonRpcCodec.decode(raw)
        val isFromAgent: Boolean get() = direction == "agent->client"
    }

    fun load(name: String): List<Frame> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "fixture $name not found"
        }
        return stream.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val obj = AcpJson.parseToJsonElement(line) as? JsonObject ?: return@mapNotNull null
                // The first line is a header (_fixture, _note), not a frame.
                if (obj.containsKey("_fixture")) return@mapNotNull null
                val direction = (obj["dir"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val frame = obj["frame"] ?: return@mapNotNull null
                Frame(direction, frame.toString())
            }
    }
}
