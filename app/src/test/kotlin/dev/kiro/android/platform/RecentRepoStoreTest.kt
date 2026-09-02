package dev.kiro.android.platform

import dev.kiro.core.session.RecentRepo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An in-memory [StringStore], mirroring [DataStoreBridgeRegistryTest]'s
 * `FakeStringStore` so [DataStoreRecentRepoStore] can be exercised as a plain
 * JVM unit test with no Android [android.content.Context] involved.
 */
private class FakeRecentStringStore : StringStore {
    var value: String? = null

    override suspend fun read(): String? = value
    override suspend fun write(value: String) {
        this.value = value
    }
}

class RecentRepoStoreTest {

    @Test
    fun `read is empty before anything is merged`() = runBlocking {
        val store = DataStoreRecentRepoStore(FakeRecentStringStore())

        assertEquals(emptyList(), store.read())
    }

    @Test
    fun `merge then read round-trips a repository`() = runBlocking {
        val store = DataStoreRecentRepoStore(FakeRecentStringStore())
        val repo = RecentRepo("owner/repo", "GITHUB", 100L)

        store.merge(listOf(repo))

        assertEquals(listOf(repo), store.read())
    }

    @Test
    fun `merge round-trips a null providerType`() = runBlocking {
        val store = DataStoreRecentRepoStore(FakeRecentStringStore())
        val repo = RecentRepo("owner/repo", null, 100L)

        store.merge(listOf(repo))

        assertEquals(listOf(repo), store.read())
    }

    @Test
    fun `merging the same slug again keeps the newer lastUsedMillis`() = runBlocking {
        val store = DataStoreRecentRepoStore(FakeRecentStringStore())

        store.merge(listOf(RecentRepo("owner/repo", "GITHUB", 1L)))
        store.merge(listOf(RecentRepo("owner/repo", "GITHUB", 99L)))

        assertEquals(listOf(RecentRepo("owner/repo", "GITHUB", 99L)), store.read())
    }

    @Test
    fun `results are sorted most-recently-used first`() = runBlocking {
        val store = DataStoreRecentRepoStore(FakeRecentStringStore())

        store.merge(
            listOf(
                RecentRepo("owner/old", "GITHUB", 1L),
                RecentRepo("owner/new", "GITHUB", 99L),
                RecentRepo("owner/mid", "GITHUB", 50L),
            ),
        )

        assertEquals(
            listOf("owner/new", "owner/mid", "owner/old"),
            store.read().map { it.slug },
        )
    }

    @Test
    fun `merge caps the store at 12 entries`() = runBlocking {
        val store = DataStoreRecentRepoStore(FakeRecentStringStore())

        val repos = (1..20).map { RecentRepo("owner/repo-$it", "GITHUB", it.toLong()) }
        store.merge(repos)

        val stored = store.read()
        assertEquals(12, stored.size)
        assertEquals(
            (20 downTo 9).map { "owner/repo-$it" },
            stored.map { it.slug },
        )
    }

    @Test
    fun `corrupt JSON returns empty rather than throwing`() = runBlocking {
        val stringStore = FakeRecentStringStore().apply { value = "{ this is not valid json array" }
        val store = DataStoreRecentRepoStore(stringStore)

        assertEquals(emptyList(), store.read())
    }
}
