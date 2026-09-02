package dev.kiro.core.session

import dev.kiro.core.model.RepoSlug
import dev.kiro.core.model.SessionSource
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.util.Logger

/** A repository the user has used before, persisted locally (ADR-004 §5 layer (b)). */
public data class RecentRepo(
    val slug: String,
    val providerType: String?,
    val lastUsedMillis: Long,
)

/**
 * Where recently-used repositories are persisted, so ADR-004 §5's "recent"
 * layer survives a cold start with no bridge attached.
 */
public interface RecentRepoStore {
    public suspend fun read(): List<RecentRepo>

    /** Upserts by slug, keeping the newer [RecentRepo.lastUsedMillis] on a collision. */
    public suspend fun merge(repos: List<RecentRepo>)
}

/**
 * A repository the create-session picker can offer, from any of ADR-004 §5's
 * three layers.
 */
public data class RepoSuggestion(
    val slug: String,
    val providerType: String?,
    val defaultBranch: String?,
    val isPrivate: Boolean,
    val origin: Origin,
) {
    public enum class Origin { CATALOG, RECENT, MANUAL }
}

/**
 * What a layer of the picker currently knows.
 *
 * The reason this exists rather than a bare list: an empty catalog because
 * `listSourceProviders`/`listRepositories` failed must not collapse into the
 * same rendering as "you really have no repositories" (ADR-004 §5, §6).
 */
public sealed interface LayerState<out T> {
    public data object Loading : LayerState<Nothing>
    public data class Ready<T>(val value: T) : LayerState<T>
    public data class Failed(val reason: String) : LayerState<Nothing>
}

/**
 * The three-layer picker ADR-004 §5 specifies, behind one seam:
 *
 *  1. [catalog] — the connected providers' repositories (Option B).
 *  2. [recent] — repositories bound to sessions this account already created
 *     (Option E), the layer that was missing entirely before this change.
 *  3. [parse] — shape-only validation of a manually typed repository (Option A),
 *     always reachable, never hidden behind a failure of the other two.
 */
public interface RepoCatalog {
    public suspend fun providers(): List<SourceProvider>

    /** Layer (a). Empty is not an error — let it propagate on failure instead. */
    public suspend fun catalog(): List<RepoSuggestion>

    /** Layer (b), most-recently-used first. Must never fail the screen. */
    public suspend fun recent(): List<RepoSuggestion>

    /** Layer (c). Cannot prove the repository exists or is reachable. */
    public fun parse(input: String): Result<RepoSuggestion>

    /** Records that these repositories were just used, for future [recent] calls. */
    public suspend fun noteUsed(slugs: List<String>, providerTypes: Map<String, String?> = emptyMap())
}

private const val MAX_RECENT = 8

/** [RepoCatalog] over a live [CloudSessionGateway] plus a persisted [RecentRepoStore]. */
public class GatewayRepoCatalog(
    private val gateway: CloudSessionGateway,
    private val store: RecentRepoStore,
    private val logger: Logger,
    private val now: () -> Long = System::currentTimeMillis,
) : RepoCatalog {

    override suspend fun providers(): List<SourceProvider> = gateway.listSourceProviders()

    /**
     * Exceptions are deliberately **not** caught here — the ViewModel is what
     * turns a failure into [LayerState.Failed]; swallowing it here is exactly
     * the "silently invisible" bug this type exists to fix.
     */
    override suspend fun catalog(): List<RepoSuggestion> {
        val connected = gateway.listSourceProviders()
            .filter { it.connectionStatus == SourceProvider.ConnectionStatus.CONNECTED }
        return connected
            .flatMap { provider -> gateway.listRepositories(provider.providerType) }
            .map { repo ->
                RepoSuggestion(
                    slug = repo.name,
                    providerType = repo.providerType,
                    defaultBranch = repo.defaultBranch,
                    isPrivate = repo.isPrivate,
                    origin = RepoSuggestion.Origin.CATALOG,
                )
            }
            .distinctBy { it.slug }
    }

    /**
     * Never throws: a failure to list sessions falls back to the persisted
     * store alone, because this layer is a convenience on top of [catalog] and
     * [parse], not something the screen should fail over.
     */
    override suspend fun recent(): List<RepoSuggestion> {
        val persisted = runCatching { store.read() }.getOrDefault(emptyList())

        val derived = runCatching { deriveFromSessions() }.getOrElse { failure ->
            logger.warn(
                "RepoCatalog: listSessions failed while deriving recents; using the persisted store only",
                failure,
            )
            emptyList()
        }

        if (derived.isNotEmpty()) {
            runCatching { store.merge(derived) }
        }

        return (derived + persisted)
            .groupBy { it.slug }
            .map { (_, entries) -> entries.maxBy { it.lastUsedMillis } }
            .sortedByDescending { it.lastUsedMillis }
            .take(MAX_RECENT)
            .map { it.toSuggestion() }
    }

    /**
     * Cloud sessions only, most-recently-updated first — `updatedAt` is already
     * ISO-8601 and therefore string-sortable, so no date parsing is needed
     * (ADR-004 §7 A10). Deduplicated by slug, keeping the most recent session's
     * occurrence.
     */
    private suspend fun deriveFromSessions(): List<RecentRepo> {
        // Descending by updatedAt, but nulls always last regardless of sort
        // direction -- `compareByDescending(nullsLast())` would put them first,
        // which is backwards for "most recent, unknown last".
        val byRecency = Comparator<String?> { a, b ->
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                else -> b.compareTo(a)
            }
        }
        val sessions = gateway.listSessions(source = SessionSource.REMOTE)
            .sortedWith(compareBy(byRecency) { it.updatedAt })
        val seenSlugs = LinkedHashSet<String>()
        val result = mutableListOf<RecentRepo>()
        sessions.forEachIndexed { sessionIndex, session ->
            session.repositories.forEach { repo ->
                if (seenSlugs.add(repo.name)) {
                    result += RecentRepo(repo.name, repo.providerType, now() - sessionIndex)
                }
            }
        }
        return result
    }

    override fun parse(input: String): Result<RepoSuggestion> =
        RepoSlug.parse(input).map { slug ->
            RepoSuggestion(
                slug = slug,
                providerType = null,
                defaultBranch = null,
                isPrivate = false,
                origin = RepoSuggestion.Origin.MANUAL,
            )
        }

    override suspend fun noteUsed(slugs: List<String>, providerTypes: Map<String, String?>) {
        val timestamp = now()
        store.merge(slugs.map { slug -> RecentRepo(slug, providerTypes[slug], timestamp) })
    }

    private fun RecentRepo.toSuggestion() = RepoSuggestion(
        slug = slug,
        providerType = providerType,
        defaultBranch = null,
        isPrivate = false,
        origin = RepoSuggestion.Origin.RECENT,
    )
}
