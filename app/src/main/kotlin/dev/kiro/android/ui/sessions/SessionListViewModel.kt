package dev.kiro.android.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kiro.android.platform.PinnedSessionStore
import dev.kiro.core.model.CloudSession
import dev.kiro.core.session.CloudSessionGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the session roster.
 *
 * Replaces the `remember {}` state that used to live directly in
 * `AppNavigation` — a `ViewModel` survives rotation and process death, which is
 * part of F-10's "done when" bar, and it is the pattern [dev.kiro.android.ui.transcript.TranscriptViewModel]
 * already establishes for this codebase.
 */
class SessionListViewModel(
    private val gateway: CloudSessionGateway,
    private val pinnedStore: PinnedSessionStore,
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<CloudSession>>(emptyList())
    private val _pinnedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Which sessions are pinned, for the star's fill state. */
    val pinnedIds: StateFlow<Set<String>> = _pinnedIds.asStateFlow()

    /**
     * The roster, pinned sessions first.
     *
     * `sortedByDescending` is a stable sort: within "pinned" and "not pinned"
     * the order the gateway (or the roster push) supplied — most recent
     * activity first — is left alone rather than reshuffled.
     *
     * `Eagerly` rather than `WhileSubscribed`: the roster is small, and this
     * keeps the combined value correct the instant the screen first collects
     * it rather than depending on subscription timing.
     */
    val sessions: StateFlow<List<CloudSession>> =
        combine(_sessions, _pinnedIds) { sessions, pinned ->
            sessions.sortedByDescending { it.id in pinned }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            _sessions.value = runCatching { gateway.listSessions() }.getOrDefault(emptyList())
        }

        // The roster pushes itself, so the list stays live without polling.
        viewModelScope.launch {
            gateway.rosterChanges.collect { change ->
                _sessions.value = (change.upserted + _sessions.value)
                    .distinctBy { it.id }
                    .filterNot { it.id in change.deleted }
            }
        }

        viewModelScope.launch {
            pinnedStore.pinnedIds.collect { _pinnedIds.value = it }
        }
    }

    /** A session created elsewhere (the create flow), added without waiting for a roster push. */
    fun addCreated(session: CloudSession) {
        _sessions.value = listOf(session) + _sessions.value
    }

    /**
     * Deletes a session.
     *
     * Cleared from the list optimistically, mirroring
     * [dev.kiro.android.ui.transcript.TranscriptViewModel.respondToPermission]:
     * the confirmation dialog already asked once, so there is nothing left to
     * double-check by leaving the row up until the round trip completes.
     */
    fun delete(sessionId: String) {
        _sessions.value = _sessions.value.filterNot { it.id == sessionId }
        viewModelScope.launch {
            runCatching { gateway.deleteSession(sessionId) }
        }
    }

    fun togglePin(sessionId: String) {
        viewModelScope.launch { pinnedStore.toggle(sessionId) }
    }
}
