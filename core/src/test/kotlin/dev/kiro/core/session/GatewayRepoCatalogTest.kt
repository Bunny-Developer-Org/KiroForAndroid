package dev.kiro.core.session

import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ExecutionTarget
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.ListScope
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SessionStatus
import dev.kiro.core.model.SourceRepo
import dev.kiro.core.util.Logger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An in-memory [RecentRepoStore], the counterpart to [FakeGateway] for these tests. */
private class InMemoryRecentRepoStore(initial: List<RecentRepo> = emptyList()) : RecentRepoStore {
    var stored: List<RecentRepo> = initial
        private set

    override suspend fun read(): List<RecentRepo> = stored

    override suspend fun merge(repos: List<RecentRepo>) {
        stored = (repos + stored)
            .groupBy { it.slug }
            .map { (_, entries) -> entries.maxBy { it.lastUsedMillis } }
            .sortedByDescending { it.lastUsedMillis }
    }
}

/** Delegates everything to a [FakeGateway] except [listSessions], which throws. */
private class ThrowingListSessionsGateway(
    private val delegate: CloudSessionGateway = FakeGateway(),
) : CloudSessionGateway by delegate {
    override suspend fun listSessions(source: SessionSource, scope: ListScope): List<CloudSession> =
        error("bridge is gone")
}

private fun session(
    id: String,
    repos: List<SourceRepo>,
    updatedAt: String?,
) = CloudSession(
    id = id,
    title = "title-$id",
    source = SessionSource.REMOTE,
    executionTarget = ExecutionTarget.CLOUD_SANDBOX,
    status = SessionStatus.IDLE,
    instanceStatus = InstanceStatus.RUNNING,
    repositories = repos,
    agentMode = "vibe",
    createdAt = null,
    updatedAt = updatedAt,
    cwd = "",
)

class GatewayRepoCatalogTest {

    @Test
    fun `catalog skips non-connected providers`() = runBlocking {
        // FakeGateway.listSourceProviders(): GITHUB connected, GITLAB not connected.
        val catalog = GatewayRepoCatalog(FakeGateway(), InMemoryRecentRepoStore(), Logger.None)

        val result = catalog.catalog()

        assertEquals(2, result.size)
        assertTrue(result.all { it.providerType == "GITHUB" })
        assertTrue(result.all { it.origin == RepoSuggestion.Origin.CATALOG })
    }

    @Test
    fun `recents are most-recently-used first, derived from session updatedAt`() = runBlocking {
        // FakeGateway's default fixture: repo-1's session updatedAt is later than repo-2's.
        val catalog = GatewayRepoCatalog(FakeGateway(), InMemoryRecentRepoStore(), Logger.None)

        val recents = catalog.recent()

        assertEquals(listOf("example-org/repo-1", "example-org/repo-2"), recents.map { it.slug })
        assertTrue(recents.all { it.origin == RepoSuggestion.Origin.RECENT })
    }

    @Test
    fun `recents dedup the same repo across multiple sessions`() = runBlocking {
        val sessions = mutableListOf(
            session("s1", listOf(SourceRepo("GITHUB", "shared/repo", null)), "2026-09-02T10:00:00.000Z"),
            session("s2", listOf(SourceRepo("GITHUB", "shared/repo", null)), "2026-09-01T10:00:00.000Z"),
        )
        val catalog = GatewayRepoCatalog(FakeGateway(sessions), InMemoryRecentRepoStore(), Logger.None)

        val recents = catalog.recent()

        assertEquals(listOf("shared/repo"), recents.map { it.slug })
    }

    @Test
    fun `a listSessions failure falls back to the persisted store alone`() = runBlocking {
        val store = InMemoryRecentRepoStore(listOf(RecentRepo("persisted/repo", "GITHUB", 1L)))
        val catalog = GatewayRepoCatalog(ThrowingListSessionsGateway(), store, Logger.None)

        val recents = catalog.recent()

        assertEquals(listOf("persisted/repo"), recents.map { it.slug })
    }

    @Test
    fun `noteUsed writes through to the store`() = runBlocking {
        val store = InMemoryRecentRepoStore()
        var clockValue = 42L
        val catalog = GatewayRepoCatalog(FakeGateway(mutableListOf()), store, Logger.None, now = { clockValue })

        catalog.noteUsed(listOf("owner/repo"), mapOf("owner/repo" to "GITLAB"))

        assertEquals(listOf(RecentRepo("owner/repo", "GITLAB", 42L)), store.read())
    }

    @Test
    fun `parse delegates to RepoSlug and wraps success as a manual suggestion`() = runBlocking {
        val catalog = GatewayRepoCatalog(FakeGateway(), InMemoryRecentRepoStore(), Logger.None)

        val result = catalog.parse("owner/repo")

        assertTrue(result.isSuccess)
        assertEquals(RepoSuggestion.Origin.MANUAL, result.getOrNull()?.origin)
        assertEquals("owner/repo", result.getOrNull()?.slug)
    }

    @Test
    fun `parse propagates the RepoSlug failure message`() = runBlocking {
        val catalog = GatewayRepoCatalog(FakeGateway(), InMemoryRecentRepoStore(), Logger.None)

        val result = catalog.parse("not a valid slug")

        assertTrue(result.isFailure)
    }
}
