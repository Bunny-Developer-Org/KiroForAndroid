package dev.kiro.core.acp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A3 was refuted: the docs disagree with themselves about the extension prefix
 * and KAS lets an operator change it. So the client derives it instead of
 * matching a constant, and these tests pin that behaviour.
 */
class ExtensionNamespaceTest {

    @Test
    fun `derives the prefix from the methods the handshake enumerates`() {
        val namespace = ExtensionNamespace.from(
            listOf(
                "_kiro/knowledge",
                "_kiro/codeIntelligence",
                "_kiro/session/context",
                "_kiro/sourceProviders/list",
            ),
        )
        assertEquals("_kiro/", namespace.prefix)
        assertEquals("_kiro/sourceProviders/list", namespace.sourceProvidersList)
    }

    @Test
    fun `follows a server that renamed its namespace`() {
        val namespace = ExtensionNamespace.from(
            listOf("_acme/knowledge", "_acme/sourceProviders/list"),
        )
        assertEquals("_acme/", namespace.prefix)
        assertEquals("_acme/permission/respond", namespace.permissionRespond)
    }

    @Test
    fun `falls back rather than failing when the agent advertises nothing`() {
        assertEquals(ExtensionNamespace.DEFAULT_PREFIX, ExtensionNamespace.from(emptyList()).prefix)
        assertEquals(
            ExtensionNamespace.DEFAULT_PREFIX,
            ExtensionNamespace.from(listOf("noSlashHere", "alsoNone")).prefix,
        )
    }
}
