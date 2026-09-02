package dev.kiro.android.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.common.NamedState
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.core.model.AgentMode
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.session.LayerState
import dev.kiro.core.session.RepoSuggestion

/**
 * The headline feature: start a cloud session from a phone.
 *
 * Three constraints are honoured in the copy rather than papered over with
 * controls that cannot work:
 *
 *  - **Repositories are fixed at creation.** There is no "add a repo later".
 *  - **Branches cannot be selected**, at creation or at attach. The default
 *    branch is shown as information, and the copy says to ask the agent instead
 *    of offering a picker that would silently do nothing.
 *  - **A provider that is not connected says so**, rather than rendering as "you
 *    have no repositories" — the failure F-11 specifically calls out.
 *
 * Takes [CreateSessionViewModel.State] rather than the view model itself, so
 * Compose previews keep working against plain data (ADR-004 §5's three picker
 * layers all show up here: catalog, recent, and manual entry).
 *
 * Sections with hairline dividers, not a stack of bordered cards: this is the
 * densest form in the app and card-per-field is exactly what "reach for a card
 * less often on a phone" warns against.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateSessionScreen(
    state: CreateSessionViewModel.State,
    modes: List<AgentMode>,
    onConnectProvider: (SourceProvider) -> Unit,
    onSetQuery: (String) -> Unit,
    onSetManualEntry: (String) -> Unit,
    onAddManual: () -> Unit,
    onToggle: (RepoSuggestion) -> Unit,
    onRemove: (String) -> Unit,
    onRetryCatalog: () -> Unit,
    onCreate: (prompt: String, modeId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    var prompt by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(KiroLayout.ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RepositorySection(
            state = state,
            onConnectProvider = onConnectProvider,
            onSetQuery = onSetQuery,
            onSetManualEntry = onSetManualEntry,
            onAddManual = onAddManual,
            onToggle = onToggle,
            onRemove = onRemove,
            onRetryCatalog = onRetryCatalog,
        )

        HorizontalDivider(color = colors.border)
        SectionLabel("Mode")

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.forEach { candidate ->
                ModePill(candidate, candidate.id == mode) { mode = candidate.id }
            }
        }

        HorizontalDivider(color = colors.border)
        SectionLabel("First prompt")

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            placeholder = { Text("What should Kiro do?") },
            shape = MaterialTheme.shapes.small,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.error != null) {
            Text(state.error, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
        }

        Button(
            onClick = { onCreate(prompt.trim(), mode) },
            // Locked during provisioning: a second tap creates a second cloud
            // session, and the preview caps you at ten.
            enabled = state.canSubmit && prompt.isNotBlank(),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.accentFg,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = KiroLayout.TouchTarget),
        ) {
            Text(
                if (state.busy) "Starting the sandbox…" else "Start session",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = KiroTheme.colors.muted)
}

/** A pill in [state.selected], removable — the fix for invisible manual entries. */
@Composable
private fun SelectedRepoPill(repo: RepoSuggestion, onRemove: () -> Unit) {
    val colors = KiroTheme.colors
    Row(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(colors.accentSubtle)
            .heightIn(min = 36.dp)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column {
            Text(repo.slug, style = MaterialTheme.typography.labelLarge, color = colors.textStrong)
            repo.defaultBranch?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.muted)
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Remove ${repo.slug}",
                tint = colors.muted,
            )
        }
    }
}

@Composable
private fun RepoPill(repo: RepoSuggestion, selected: Boolean, onToggle: () -> Unit) {
    val colors = KiroTheme.colors
    Row(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) colors.accentSubtle else colors.bgElevated)
            .clickable(onClick = onToggle)
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            repo.slug,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.textStrong else colors.text,
        )
        // Shown, not editable. See the class comment.
        repo.defaultBranch?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.muted)
        }
        if (repo.isPrivate) {
            Text("private", style = MaterialTheme.typography.labelMedium, color = colors.muted)
        }
    }
}

@Composable
private fun ModePill(mode: AgentMode, selected: Boolean, onSelect: () -> Unit) {
    val colors = KiroTheme.colors
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) colors.accentSubtle else colors.bgElevated)
            .clickable(onClick = onSelect)
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            mode.name,
            style = MaterialTheme.typography.labelLarge,
            // Autonomy carries colour so the risk gradient is visible without
            // reading: this is the mode that acts unsupervised.
            color = if (mode.id == "autonomous") colors.warn else colors.text,
        )
    }
}

/**
 * Repository selection, extracted because it is the densest part of the densest
 * screen — and because the three constraints it has to communicate (fixed at
 * creation, no branch selection, provider must be connected) belong together.
 *
 * Rendering order matches ADR-004 §5: selected pills, then not-connected
 * providers, then recent repos, then a search box over the catalog, then
 * manual entry as a permanent fallback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepositorySection(
    state: CreateSessionViewModel.State,
    onConnectProvider: (SourceProvider) -> Unit,
    onSetQuery: (String) -> Unit,
    onSetManualEntry: (String) -> Unit,
    onAddManual: () -> Unit,
    onToggle: (RepoSuggestion) -> Unit,
    onRemove: (String) -> Unit,
    onRetryCatalog: () -> Unit,
) {
    val colors = KiroTheme.colors
    val selectedSlugs = state.selected.map { it.slug }.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Repositories")

        if (state.selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.selected.forEach { repo ->
                    SelectedRepoPill(repo = repo, onRemove = { onRemove(repo.slug) })
                }
            }
        }

        NotConnectedProviderLines(state.providers, onConnectProvider)
        RecentReposSection(state.recents, selectedSlugs, onToggle)

        OutlinedTextField(
            value = state.query,
            onValueChange = onSetQuery,
            label = { Text("Search your repositories") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )

        CatalogSection(state, selectedSlugs, onToggle, onConnectProvider, onRetryCatalog)
        ManualEntrySection(state, onSetManualEntry, onAddManual)

        if (state.selected.isNotEmpty()) {
            Text(
                "Repositories are fixed once the session starts, and Kiro cannot be " +
                    "pointed at a branch — ask the agent to check one out or create one " +
                    "after it is running.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun NotConnectedProviderLines(
    providers: List<SourceProvider>,
    onConnectProvider: (SourceProvider) -> Unit,
) {
    val colors = KiroTheme.colors
    providers.filter { it.connectionStatus != SourceProvider.ConnectionStatus.CONNECTED }
        .forEach { provider ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = KiroLayout.TouchTarget)
                    .clickable { onConnectProvider(provider) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${provider.displayName ?: provider.providerType} is not connected to " +
                        "your Kiro account. Connect it in Kiro's settings to see its " +
                        "repositories here.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.warn,
                )
            }
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentReposSection(
    recents: List<RepoSuggestion>,
    selectedSlugs: Set<String>,
    onToggle: (RepoSuggestion) -> Unit,
) {
    val notYetSelected = recents.filterNot { it.slug in selectedSlugs }
    if (notYetSelected.isEmpty()) return

    SectionLabel("Recent")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        notYetSelected.forEach { repo ->
            RepoPill(repo = repo, selected = false, onToggle = { onToggle(repo) })
        }
    }
}

/** Loading / failed / ready-but-empty, per ADR-004 §5 -- never a bare empty area. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogSection(
    state: CreateSessionViewModel.State,
    selectedSlugs: Set<String>,
    onToggle: (RepoSuggestion) -> Unit,
    onConnectProvider: (SourceProvider) -> Unit,
    onRetryCatalog: () -> Unit,
) {
    val colors = KiroTheme.colors
    when (val catalog = state.catalog) {
        LayerState.Loading -> Text(
            "Loading your repositories…",
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
        )

        is LayerState.Failed -> NamedState(
            title = "Could not load your repositories",
            detail = catalog.reason,
            action = {
                TextButton(onClick = onRetryCatalog) { Text("Try again") }
            },
        )

        is LayerState.Ready -> {
            val allDisconnected = state.providers.isNotEmpty() &&
                state.providers.all { it.connectionStatus != SourceProvider.ConnectionStatus.CONNECTED }
            if (allDisconnected) {
                NamedState(
                    title = "Connect a provider to see your repositories",
                    detail = "Kiro needs a connected GitHub or GitLab account before it can " +
                        "list repositories here.",
                    action = {
                        TextButton(onClick = { state.providers.firstOrNull()?.let(onConnectProvider) }) {
                            Text("Open Kiro settings")
                        }
                    },
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.filteredCatalog.forEach { repo ->
                        RepoPill(
                            repo = repo,
                            selected = repo.slug in selectedSlugs,
                            onToggle = { onToggle(repo) },
                        )
                    }
                }
            }
        }
    }
}

/** A permanent affordance, never a failure state: the catalog can come back
 * empty for reasons unrelated to the user's repositories existing. */
@Composable
private fun ManualEntrySection(
    state: CreateSessionViewModel.State,
    onSetManualEntry: (String) -> Unit,
    onAddManual: () -> Unit,
) {
    val colors = KiroTheme.colors
    OutlinedTextField(
        value = state.manualEntry,
        onValueChange = onSetManualEntry,
        label = { Text("Or type owner/repo") },
        isError = state.manualError != null,
        supportingText = {
            Text(
                state.manualError ?: "If a repository is rejected, the Kiro Agent app is " +
                    "most likely not installed for it.",
                color = if (state.manualError != null) colors.danger else colors.muted,
            )
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.manualEntry.isNotBlank()) {
        Text(
            "Add \"${state.manualEntry.trim()}\"",
            style = MaterialTheme.typography.labelLarge,
            color = colors.accent,
            modifier = Modifier
                .heightIn(min = KiroLayout.TouchTarget)
                .clickable(onClick = onAddManual),
        )
    }
}
