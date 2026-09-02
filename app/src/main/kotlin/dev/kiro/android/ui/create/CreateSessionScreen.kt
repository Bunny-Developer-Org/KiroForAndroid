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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.core.model.AgentMode
import dev.kiro.core.model.RepoCandidate
import dev.kiro.core.model.SourceProvider

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
 * Sections with hairline dividers, not a stack of bordered cards: this is the
 * densest form in the app and card-per-field is exactly what "reach for a card
 * less often on a phone" warns against.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateSessionScreen(
    providers: List<SourceProvider>,
    repositories: List<RepoCandidate>,
    modes: List<AgentMode>,
    busy: Boolean,
    errorMessage: String?,
    onConnectProvider: (SourceProvider) -> Unit,
    onCreate: (repos: List<String>, prompt: String, modeId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var manualEntry by remember { mutableStateOf("") }
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
            providers = providers,
            repositories = repositories,
            selected = selected,
            manualEntry = manualEntry,
            onManualEntryChange = { manualEntry = it },
            onToggle = { name ->
                selected = if (name in selected) selected - name else selected + name
            },
            onConnectProvider = onConnectProvider,
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

        if (errorMessage != null) {
            Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
        }

        Button(
            onClick = { onCreate(selected.toList(), prompt.trim(), mode) },
            // Locked during provisioning: a second tap creates a second cloud
            // session, and the preview caps you at ten.
            enabled = !busy && selected.isNotEmpty() && prompt.isNotBlank(),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.accentFg,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = KiroLayout.TouchTarget),
        ) {
            Text(
                if (busy) "Starting the sandbox…" else "Start session",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = KiroTheme.colors.muted)
}

@Composable
private fun RepoPill(repo: RepoCandidate, selected: Boolean, onToggle: () -> Unit) {
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
            repo.name,
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepositorySection(
    providers: List<SourceProvider>,
    repositories: List<RepoCandidate>,
    selected: Set<String>,
    manualEntry: String,
    onManualEntryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onConnectProvider: (SourceProvider) -> Unit,
) {
    val colors = KiroTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Repositories")

        providers.filter { it.connectionStatus != SourceProvider.ConnectionStatus.CONNECTED }
            .forEach { provider ->
                Text(
                    "${provider.displayName ?: provider.providerType} is not connected to " +
                        "your Kiro account. Connect it in Kiro's settings to see its " +
                        "repositories here.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.warn,
                    modifier = Modifier.clickable { onConnectProvider(provider) },
                )
            }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repositories.forEach { repo ->
                RepoPill(
                    repo = repo,
                    selected = repo.name in selected,
                    onToggle = { onToggle(repo.name) },
                )
            }
        }

        // A permanent affordance, never a failure state: the catalog can come back
        // empty for reasons unrelated to the user's repositories existing.
        OutlinedTextField(
            value = manualEntry,
            onValueChange = onManualEntryChange,
            label = { Text("Or type owner/repo") },
            supportingText = {
                Text(
                    "If a repository is rejected, the Kiro Agent app is most likely not " +
                        "installed for it.",
                )
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        if (manualEntry.contains('/') && manualEntry !in selected) {
            Text(
                "Add \"$manualEntry\"",
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
                modifier = Modifier
                    .heightIn(min = KiroLayout.TouchTarget)
                    .clickable {
                        onToggle(manualEntry.trim())
                        onManualEntryChange("")
                    },
            )
        }

        if (selected.isNotEmpty()) {
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
