package dev.kiro.android.ui.create

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.common.NamedState
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroMotion
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.android.ui.theme.LocalReduceMotion
import dev.kiro.core.model.SourceProvider
import dev.kiro.core.session.LayerState
import dev.kiro.core.session.RepoSuggestion

/**
 * Repository selection, extracted because it is the densest part of the densest
 * screen — and because the three constraints it has to communicate (fixed at
 * creation, no branch selection, provider must be connected) belong together.
 *
 * **Progressive disclosure, added after the picker was seen on a 1080x2400
 * device:** an account with a couple of dozen repositories rendered every one of
 * them inline, pushing Mode, First prompt and Start session below two screens of
 * pills — the form's own controls lost to its first field. The catalog, recents,
 * search and manual entry now live behind a closed control, and picking a
 * repository closes it again.
 *
 * That does not weaken ADR-004 §5's rule that *manual entry is never hidden
 * behind a failure state*: it is still one permanent affordance inside the one
 * picker, reachable identically whether the catalog loaded or failed — a
 * disclosure, not a fallback screen. A failed catalog is announced while closed
 * ([CollapsedCatalogNote]) precisely so the state is not silent.
 *
 * Rendering order inside the open picker matches ADR-004 §5: selected pills,
 * then not-connected providers, then recent repos, then a search box over the
 * catalog, then manual entry as a permanent fallback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepositoryPicker(
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
    val reduceMotion = LocalReduceMotion.current
    val selectedSlugs = state.selected.map { it.slug }.toSet()
    var open by rememberSaveable { mutableStateOf(false) }
    var lastSelectedCount by rememberSaveable { mutableIntStateOf(state.selected.size) }

    // Closing on the selection *growing*, rather than from each tap handler, is
    // one rule covering both ways a repository can be added. Manual entry is the
    // reason: addManual() reports a bad slug by setting State.manualError and
    // leaving the selection alone, so a handler that closed on tap would hide
    // the error the user has to read. Deselecting leaves the picker open.
    LaunchedEffect(state.selected.size) {
        if (state.selected.size > lastSelectedCount) open = false
        lastSelectedCount = state.selected.size
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Repositories")

        if (state.selected.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.selected.forEach { repo ->
                    SelectedRepoPill(repo = repo, onRemove = { onRemove(repo.slug) })
                }
                AddRepoPill(open = open, onClick = { open = !open })
            }
        } else {
            ClosedPickerRow(
                open = open,
                catalog = state.catalog,
                onClick = { open = !open },
            )
        }

        if (!open) CollapsedCatalogNote(state.catalog)

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(
                tween(KiroMotion.duration(KiroMotion.SLIDE_UP_MILLIS, reduceMotion)),
            ) + expandVertically(
                tween(
                    KiroMotion.duration(KiroMotion.SLIDE_UP_MILLIS, reduceMotion),
                    easing = KiroMotion.Ease,
                ),
            ),
            exit = fadeOut(
                tween(KiroMotion.duration(KiroMotion.SHEET_OUT_MILLIS, reduceMotion)),
            ) + shrinkVertically(
                tween(
                    KiroMotion.duration(KiroMotion.SHEET_OUT_MILLIS, reduceMotion),
                    easing = KiroMotion.SheetOut,
                ),
            ),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        }

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

/** The closed control, shown until the first repository is chosen. */
@Composable
private fun ClosedPickerRow(
    open: Boolean,
    catalog: LayerState<List<RepoSuggestion>>,
    onClick: () -> Unit,
) {
    val colors = KiroTheme.colors
    val shape = MaterialTheme.shapes.small
    val available = (catalog as? LayerState.Ready)?.value?.size ?: 0
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .heightIn(min = KiroLayout.TouchTarget)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Choose a repository",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        if (available > 0) {
            Text(
                "$available available",
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }
        Icon(
            imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Hide the repository list" else "Show the repository list",
            tint = colors.muted,
        )
    }
}

/** Reopens the picker for a second repository — sessions bind a list, not one repo. */
@Composable
private fun AddRepoPill(open: Boolean, onClick: () -> Unit) {
    val colors = KiroTheme.colors
    val shape = MaterialTheme.shapes.extraSmall
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, colors.border, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.Add,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (open) "Close" else "Add another",
            style = MaterialTheme.typography.labelLarge,
            color = colors.accent,
        )
    }
}

/**
 * A closed picker must not swallow a failed catalog: `_kiro/sourceProviders/list`
 * failing is routine (an unconnected GitHub/GitLab account reaches the UI the
 * same way), and the retry plus manual entry that answer it are inside.
 */
@Composable
private fun CollapsedCatalogNote(catalog: LayerState<List<RepoSuggestion>>) {
    if (catalog !is LayerState.Failed) return
    Text(
        "Your repository list could not be loaded — open the picker to try again, " +
            "or to type an owner/repo yourself.",
        style = MaterialTheme.typography.labelMedium,
        color = KiroTheme.colors.warn,
    )
}

/** A pill in [CreateSessionViewModel.State.selected], removable — the fix for invisible manual entries. */
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
        // Shown, not editable. See CreateSessionScreen's class comment.
        repo.defaultBranch?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.muted)
        }
        if (repo.isPrivate) {
            Text("private", style = MaterialTheme.typography.labelMedium, color = colors.muted)
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
