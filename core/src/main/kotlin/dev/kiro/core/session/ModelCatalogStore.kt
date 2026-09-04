package dev.kiro.core.session

import dev.kiro.core.model.KiroModel

/**
 * The last model catalogue the agent showed us, kept across process restarts.
 *
 * This exists because of a hole in the protocol, not to save a round trip:
 * **there is no way to list models without a session.** The `initialize`
 * handshake enumerates 24 extension methods and none of them is a model catalogue;
 * the list only ever arrives attached to a session's config options, and for a
 * *cloud* session not even at `session/new` — the sandbox pushes it later over
 * `config_option_update` (PROTOCOL-FINDINGS §4d).
 *
 * [ModelState.lastKnownCatalog] already carries that list forward within one
 * connection, which is enough for a create screen opened after some session has
 * been attached and useless for the far more common case: the app cold-starting
 * and the user going straight to "New session". Writing the catalogue down is the
 * only way a phone can answer "which model?" before the sandbox that would tell
 * it exists.
 *
 * A remembered catalogue is a *last known* list, never an authority. What the
 * session actually runs is still whatever the agent confirms afterwards.
 *
 * Implemented in `app/` for the same reason [dev.kiro.core.auth.TokenStore] is:
 * `core/` may not touch Android (ADR-003 §2).
 */
public interface ModelCatalogStore {

    public suspend fun read(): List<KiroModel>

    public suspend fun write(models: List<KiroModel>)

    public companion object {
        /** Remembers nothing, so no caller is forced to have an opinion. */
        public val None: ModelCatalogStore = object : ModelCatalogStore {
            override suspend fun read(): List<KiroModel> = emptyList()
            override suspend fun write(models: List<KiroModel>) = Unit
        }
    }
}
