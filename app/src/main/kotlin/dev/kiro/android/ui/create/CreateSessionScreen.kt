package dev.kiro.android.ui.create

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.android.ui.transcript.formatRate
import dev.kiro.core.model.AgentMode
import dev.kiro.core.model.KiroModel
import dev.kiro.core.model.SourceProvider
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
 *  - **The model list may not exist yet.** Nothing in the protocol lists models
 *    without a session, so this screen offers the last list the agent published
 *    and says plainly when it has none — a picker cannot be conjured from an
 *    empty catalogue, and pretending otherwise would name models that may not be
 *    on offer (PROTOCOL-FINDINGS §4d).
 *
 * Takes [CreateSessionViewModel.State] rather than the view model itself, so
 * Compose previews keep working against plain data. ADR-004 §5's three picker
 * layers — catalog, recent, and manual entry — live in [RepositoryPicker], which
 * keeps them behind one closed control so this form's own Mode, First prompt and
 * Start session controls stay on the first screen.
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
    onSetModel: (String?) -> Unit,
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
        RepositoryPicker(
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

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            modes.forEach { candidate ->
                ModePill(candidate, candidate.id == mode) { mode = candidate.id }
            }
        }

        HorizontalDivider(color = colors.border)
        SectionLabel("Model")
        ModelSection(state.models, state.modelId, onSetModel)

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

/** Shared with [RepositoryPicker], which is the other half of this form. */
@Composable
internal fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = KiroTheme.colors.muted)
}

@Composable
private fun ModePill(mode: AgentMode, selected: Boolean, onSelect: () -> Unit) {
    ChoicePill(
        label = mode.name,
        selected = selected,
        // Autonomy carries colour so the risk gradient is visible without
        // reading: this is the mode that acts unsupervised.
        color = if (mode.id == "autonomous") KiroTheme.colors.warn else null,
        onSelect = onSelect,
    )
}

/**
 * The model choice, or an honest account of why there is not one.
 *
 * Two genuinely different states, and neither is an empty list pretending to be
 * a picker. With a catalogue: "Whatever Kiro picks" first, then every model with
 * its credit multiplier, because the catalogue spans 0.05x to 2.2x and at that
 * spread the model *is* the price — the same reasoning as
 * [dev.kiro.android.ui.transcript.ModelBar], and a figure is omitted rather than
 * invented wherever the agent gave none. Without one: a sentence saying so and
 * where the choice does exist, since there is nothing here to retry.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSection(models: List<KiroModel>, selected: String?, onSelect: (String?) -> Unit) {
    val colors = KiroTheme.colors

    if (models.isEmpty()) {
        Text(
            "Kiro only lists its models once a session is open, and none has been seen on " +
                "this bridge yet. Start the session and pick a model from the transcript — " +
                "it takes effect from your next message.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        return
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "No preference" is the state this screen starts in, so it is on the list
        // as its own choice rather than implied by the absence of a selection —
        // and it is not the same thing as the catalogue's own `auto`, which is a
        // named model with a rate. This one sends nothing and lets the session
        // start on whatever the agent picks.
        ChoicePill("Kiro's default", selected == null) { onSelect(null) }
        models.forEach { model ->
            val rate = formatRate(model.rateMultiplier, model.rateUnit)
            ChoicePill(
                label = if (rate == null) model.name else "${model.name}  $rate",
                selected = model.id == selected,
                onSelect = { onSelect(model.id) },
            )
        }
    }

    // Only once something has actually been asked for: with no choice made there
    // is no switch to explain, and the sentence would just be noise on a form
    // that already carries a lot of it.
    if (selected != null) {
        Text(
            "The sandbox is created first and switched afterwards, so a model Kiro refuses " +
                "leaves the session running on its default rather than failing to start.",
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
        )
    }
}

/** The shared pill shape behind both the Mode and Model rows. */
@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    color: Color? = null,
    onSelect: () -> Unit,
) {
    val colors = KiroTheme.colors
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) colors.accentSubtle else colors.bgElevated)
            // Role.RadioButton so TalkBack announces which one is chosen instead
            // of reading four equal-sounding buttons.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = KiroLayout.TouchTarget)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color ?: colors.text)
    }
}
