package dev.kiro.android.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.session.CloudSessionGateway
import dev.kiro.core.session.CreateSessionRequest
import dev.kiro.core.session.LayerState
import dev.kiro.core.session.NotEntitledException
import dev.kiro.core.session.PromptBlock
import dev.kiro.core.session.RepoCatalog
import dev.kiro.core.session.RepoSuggestion
import dev.kiro.core.session.SessionLimitReachedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the create-session screen's state: the three ADR-004 §5 picker layers,
 * the user's selection, and the create round trip.
 *
 * Modeled on [dev.kiro.android.ui.sessions.SessionListViewModel]'s
 * constructor-injection + [MutableStateFlow] + `viewModelScope` pattern. The
 * `create()` body is relocated verbatim from `AppNavigation.kt`'s old
 * `CreateScreenHost.onCreate` handler, including its exception mapping.
 */
class CreateSessionViewModel(
    private val gateway: CloudSessionGateway,
    private val catalog: RepoCatalog,
    initialRepos: List<String> = emptyList(),
) : ViewModel() {

    data class State(
        val providers: List<SourceProvider> = emptyList(),
        val catalog: LayerState<List<RepoSuggestion>> = LayerState.Loading,
        val recents: List<RepoSuggestion> = emptyList(),
        val selected: List<RepoSuggestion> = emptyList(),
        val query: String = "",
        val manualEntry: String = "",
        val manualError: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        val filteredCatalog: List<RepoSuggestion>
            get() {
                val ready = (catalog as? LayerState.Ready)?.value ?: return emptyList()
                if (query.isBlank()) return ready
                return ready.filter { it.slug.contains(query, ignoreCase = true) }
            }

        val canSubmit: Boolean get() = !busy && selected.isNotEmpty()
    }

    private val _state = MutableStateFlow(
        State(
            selected = initialRepos.map { slug ->
                RepoSuggestion(slug, null, null, false, RepoSuggestion.Origin.MANUAL)
            },
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val providers = runCatching { catalog.providers() }
            val catalogResult = runCatching { catalog.catalog() }
            _state.update { current ->
                current.copy(
                    providers = providers.getOrDefault(current.providers),
                    catalog = catalogResult.fold(
                        onSuccess = { LayerState.Ready(it) },
                        onFailure = { failure ->
                            LayerState.Failed(failure.message ?: "Could not load your repositories.")
                        },
                    ),
                    selected = upgradeSelected(current.selected, catalogResult.getOrNull().orEmpty()),
                )
            }
        }

        viewModelScope.launch {
            val recents = runCatching { catalog.recent() }.getOrDefault(emptyList())
            _state.update { it.copy(recents = recents) }
        }
    }

    /**
     * A manually-entered selection is upgraded to a catalog-origin one once the
     * catalog arrives and its slug matches, so the pill can show
     * [RepoSuggestion.defaultBranch] instead of staying a bare typed string.
     */
    private fun upgradeSelected(
        selected: List<RepoSuggestion>,
        catalogRepos: List<RepoSuggestion>,
    ): List<RepoSuggestion> {
        if (catalogRepos.isEmpty()) return selected
        val bySlug = catalogRepos.associateBy { it.slug }
        return selected.map { repo ->
            if (repo.origin == RepoSuggestion.Origin.MANUAL) bySlug[repo.slug] ?: repo else repo
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun setManualEntry(value: String) {
        _state.update { it.copy(manualEntry = value, manualError = null) }
    }

    fun toggle(suggestion: RepoSuggestion) {
        _state.update { current ->
            val alreadySelected = current.selected.any { it.slug == suggestion.slug }
            current.copy(
                selected = if (alreadySelected) {
                    current.selected.filterNot { it.slug == suggestion.slug }
                } else {
                    current.selected + suggestion
                },
            )
        }
    }

    fun remove(slug: String) {
        _state.update { it.copy(selected = it.selected.filterNot { repo -> repo.slug == slug }) }
    }

    /** Uses [RepoCatalog.parse]; on failure sets [State.manualError] and never touches [State.selected]. */
    fun addManual() {
        val input = _state.value.manualEntry
        catalog.parse(input).fold(
            onSuccess = { suggestion ->
                _state.update { current ->
                    val already = current.selected.any { it.slug == suggestion.slug }
                    current.copy(
                        selected = if (already) current.selected else current.selected + suggestion,
                        manualEntry = "",
                        manualError = null,
                    )
                }
            },
            onFailure = { failure ->
                _state.update { it.copy(manualError = failure.message ?: "Not a valid repository.") }
            },
        )
    }

    fun retryCatalog() {
        _state.update { it.copy(catalog = LayerState.Loading) }
        viewModelScope.launch {
            val result = runCatching { catalog.catalog() }
            _state.update { current ->
                current.copy(
                    catalog = result.fold(
                        onSuccess = { LayerState.Ready(it) },
                        onFailure = { failure ->
                            LayerState.Failed(failure.message ?: "Could not load your repositories.")
                        },
                    ),
                    selected = upgradeSelected(current.selected, result.getOrNull().orEmpty()),
                )
            }
        }
    }

    fun create(prompt: String, modeId: String?, onCreated: (CloudSession) -> Unit) {
        val repos = _state.value.selected
        if (repos.isEmpty()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching {
                gateway.createSession(CreateSessionRequest(repos.map { it.slug }, prompt, modeId))
            }.onSuccess { created ->
                gateway.prompt(created.id, listOf(PromptBlock.Text(prompt)))
                catalog.noteUsed(repos.map { it.slug }, repos.associate { it.slug to it.providerType })
                onCreated(created)
            }.onFailure { failure ->
                // Each of these has a different thing the user can do about it,
                // so each gets its own sentence rather than a generic failure.
                _state.update { current ->
                    current.copy(
                        error = when (failure) {
                            is NotEntitledException -> failure.message
                            is SessionLimitReachedException -> failure.message
                            else -> "Could not start the session: ${failure.message}"
                        },
                    )
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }
}
