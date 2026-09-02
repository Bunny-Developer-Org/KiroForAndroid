package dev.kiro.android.platform

import dev.kiro.core.auth.PairedBridge
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An in-memory [StringStore], so [DataStoreBridgeRegistry] can be exercised as
 * a plain JVM unit test with no Android [android.content.Context] and no real
 * DataStore Preferences instance in play.
 */
private class FakeStringStore : StringStore {
    var value: String? = null

    override suspend fun read(): String? = value
    override suspend fun write(value: String) {
        this.value = value
    }
}

class DataStoreBridgeRegistryTest {

    private fun bridge(
        id: String,
        displayName: String = id,
        url: String = "ws://$id",
        lastSeenMillis: Long? = null,
        authMode: PairedBridge.AuthMode = PairedBridge.AuthMode.CLI_LOGIN,
    ) = PairedBridge(id, displayName, url, lastSeenMillis, authMode)

    @Test
    fun `list is empty before anything is added`() = runBlocking {
        val registry = DataStoreBridgeRegistry(FakeStringStore())

        assertEquals(emptyList(), registry.list())
    }

    @Test
    fun `add then list round-trips a bridge, including a null lastSeenMillis`() = runBlocking {
        val registry = DataStoreBridgeRegistry(FakeStringStore())
        val pi = bridge(id = "pi", authMode = PairedBridge.AuthMode.CLI_LOGIN)

        registry.add(pi)

        assertEquals(listOf(pi), registry.list())
    }

    @Test
    fun `a second bridge is kept alongside the first -- this is a list, not a slot`() = runBlocking {
        val registry = DataStoreBridgeRegistry(FakeStringStore())
        val pi = bridge(id = "pi")
        val laptop = bridge(id = "laptop", lastSeenMillis = 123L, authMode = PairedBridge.AuthMode.API_KEY)

        registry.add(pi)
        registry.add(laptop)

        assertEquals(listOf(pi, laptop), registry.list())
    }

    @Test
    fun `adding a bridge with an id already present replaces it rather than duplicating it`() = runBlocking {
        val registry = DataStoreBridgeRegistry(FakeStringStore())
        val original = bridge(id = "pi", displayName = "Pi (old name)")
        val renamed = original.copy(displayName = "Pi (new name)")

        registry.add(original)
        registry.add(renamed)

        assertEquals(listOf(renamed), registry.list())
    }

    @Test
    fun `remove drops only the matching bridge`() = runBlocking {
        val registry = DataStoreBridgeRegistry(FakeStringStore())
        val pi = bridge(id = "pi")
        val laptop = bridge(id = "laptop")
        registry.add(pi)
        registry.add(laptop)

        registry.remove("pi")

        assertEquals(listOf(laptop), registry.list())
    }

    @Test
    fun `removing an unknown id is a no-op`() = runBlocking {
        val registry = DataStoreBridgeRegistry(FakeStringStore())
        val pi = bridge(id = "pi")
        registry.add(pi)

        registry.remove("does-not-exist")

        assertEquals(listOf(pi), registry.list())
    }

    @Test
    fun `touch updates lastSeenMillis for the matching bridge only`() = runBlocking {
        val registry = DataStoreBridgeRegistry(FakeStringStore())
        val pi = bridge(id = "pi", lastSeenMillis = 1L)
        val laptop = bridge(id = "laptop", lastSeenMillis = 5L)
        registry.add(pi)
        registry.add(laptop)

        registry.touch("pi", 42L)

        val byId = registry.list().associateBy { it.id }
        assertEquals(42L, byId.getValue("pi").lastSeenMillis)
        assertEquals(5L, byId.getValue("laptop").lastSeenMillis)
    }

    @Test
    fun `removing the last bridge leaves the store empty again`() = runBlocking {
        val registry = DataStoreBridgeRegistry(FakeStringStore())
        val pi = bridge(id = "pi")
        registry.add(pi)

        registry.remove("pi")

        assertEquals(emptyList(), registry.list())
        assertNull(registry.list().firstOrNull())
    }
}
