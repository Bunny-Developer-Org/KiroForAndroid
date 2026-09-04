package dev.kiro.android.platform

import dev.kiro.core.model.KiroModel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** In-memory [StringStore], same shape as [RecentRepoStoreTest]'s. */
private class FakeCatalogStringStore(var value: String? = null) : StringStore {
    override suspend fun read(): String? = value
    override suspend fun write(value: String) {
        this.value = value
    }
}

/**
 * The remembered model catalogue is the only thing standing between a cold start
 * and a create screen with no model picker at all — nothing in the protocol lists
 * models without a session (PROTOCOL-FINDINGS §4d). So the round trip has to hold,
 * and a file that has gone bad has to degrade to "no list" rather than to a crash
 * on the way to the screen.
 */
class ModelCatalogStoreTest {

    @Test
    fun `read is empty before anything is written`() = runBlocking {
        assertEquals(emptyList(), DataStoreModelCatalogStore(FakeCatalogStringStore()).read())
    }

    @Test
    fun `a model round-trips with its rate intact`() = runBlocking {
        val store = DataStoreModelCatalogStore(FakeCatalogStringStore())
        val models = listOf(
            KiroModel("auto", "Auto", "Kiro picks"),
            KiroModel("claude-opus-5", "Opus 5", null, rateMultiplier = 2.2, rateUnit = "Credit"),
        )

        store.write(models)

        assertEquals(models, store.read())
    }

    /**
     * The rate is stored as a string so a locale with a comma decimal separator
     * cannot turn 2.2 into something that will not parse back.
     */
    @Test
    fun `a fractional rate survives as a number, not as text`() = runBlocking {
        val store = DataStoreModelCatalogStore(FakeCatalogStringStore())

        store.write(listOf(KiroModel("m", "M", null, rateMultiplier = 0.05, rateUnit = "Credit")))

        assertEquals(0.05, store.read().single().rateMultiplier)
    }

    /** A whole-list replace: a model Kiro has withdrawn must not linger. */
    @Test
    fun `writing replaces rather than merges`() = runBlocking {
        val store = DataStoreModelCatalogStore(FakeCatalogStringStore())
        store.write(listOf(KiroModel("old", "Old", null)))

        store.write(listOf(KiroModel("new", "New", null)))

        assertEquals(listOf("new"), store.read().map { it.id })
    }

    @Test
    fun `an unreadable file reads as no catalogue rather than throwing`() = runBlocking {
        val store = DataStoreModelCatalogStore(FakeCatalogStringStore("not json at all"))

        assertEquals(emptyList(), store.read())
    }

    /** One bad entry costs that entry, not the whole picker. */
    @Test
    fun `an entry with no id is skipped and the rest survive`() = runBlocking {
        val store = DataStoreModelCatalogStore(
            FakeCatalogStringStore("""[{"name":"Nameless"},{"id":"ok","name":"Fine"}]"""),
        )

        assertEquals(listOf("ok"), store.read().map { it.id })
    }
}
