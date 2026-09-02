package dev.kiro.core.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonRpcCodecTest {

    @Test
    fun `classifies the three message kinds`() {
        assertIs<RpcRequest>(JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))
        assertIs<RpcNotification>(JsonRpcCodec.decode("""{"jsonrpc":"2.0","method":"session/update"}"""))
        assertIs<RpcResponse>(JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
    }

    @Test
    fun `accepts string ids as well as numeric ones`() {
        val message = JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":"abc","method":"x"}""")
        assertEquals(RpcId.Text("abc"), assertIs<RpcRequest>(message).id)
    }

    /**
     * The requirement ADR-003 §3 calls non-negotiable: a frame we cannot read is
     * data to be counted, never an exception that takes the session down.
     */
    @Test
    fun `malformed input never throws`() {
        val cases = listOf(
            "",
            "not json at all",
            "[1,2,3]",
            "null",
            """{"jsonrpc":"2.0"}""",
            """{"jsonrpc":"2.0","id":{"nested":true},"result":{}}""",
        )
        cases.forEach { input ->
            assertIs<RpcMalformed>(JsonRpcCodec.decode(input), "expected malformed for: $input")
        }
    }

    @Test
    fun `a response whose id cannot be read is malformed rather than silently dropped`() {
        val message = JsonRpcCodec.decode("""{"jsonrpc":"2.0","id":null,"result":{}}""")
        val malformed = assertIs<RpcMalformed>(message)
        assertTrue(malformed.reason.contains("id"))
    }

    @Test
    fun `errors survive the round trip with their data payload`() {
        val raw = """
            {"jsonrpc":"2.0","id":2,"error":{"code":-32000,
             "message":"Authentication required or access denied.",
             "data":{"errorType":"UnauthorizedException","requestId":"abc-123"}}}
        """.trimIndent()
        val response = assertIs<RpcResponse>(JsonRpcCodec.decode(raw))
        val error = requireNotNull(response.error)
        assertEquals(-32000, error.code)

        // The A18 probe captured exactly this shape by driving a remote session/list
        // with an invalid key. It is what the app's auth states key off, because
        // F-01 established there is no entitlement probe to ask instead.
        val exception = AcpRemoteException(error, "session/list")
        assertTrue(exception.isAuthFailure)
        assertEquals("UnauthorizedException", exception.errorType)
        assertEquals("abc-123", exception.requestId)
    }

    @Test
    fun `encoding a notification omits the id entirely`() {
        val encoded = JsonRpcCodec.encode(RpcNotification("session/cancel"))
        assertTrue(!encoded.contains("\"id\""))
        assertNull(assertIs<RpcNotification>(JsonRpcCodec.decode(encoded)).params)
    }
}
