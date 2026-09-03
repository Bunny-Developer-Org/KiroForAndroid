package dev.kiro.android.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.common.HairlineSurface
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.core.model.KiroModel
import dev.kiro.core.model.ModelSelection

/**
 * The model strip, wired to one session's [TranscriptViewModel].
 *
 * Exists as a separate overload from the stateless [ModelBar] so the host screen
 * needs one line and no knowledge of [ModelChange], while the rendering stays a
 * pure function of state.
 */
@Composable
fun SessionModelBar(viewModel: TranscriptViewModel, modifier: Modifier = Modifier) {
    val selection by viewModel.models.collectAsState()
    val change by viewModel.modelChange.collectAsState()
    ModelBar(
        selection = selection,
        change = change,
        onSelect = viewModel::setModel,
        onDismissError = viewModel::dismissModelChangeError,
        modifier = modifier,
    )
}

/**
 * Which model this session is running, stated on the transcript itself.
 *
 * Always on screen rather than behind a menu, and always carrying the credit
 * multiplier when the agent gave one. The catalogue spans 0.05× to 2.2×, a
 * factor of forty-four: at that spread the model *is* the price, and a picker
 * that named models without pricing them would let someone spend twenty times
 * what they meant to on a choice they thought was cosmetic. Where
 * [KiroModel.rateMultiplier] is absent the figure is simply omitted -- the
 * protocol's silence means "not stated", never "free" (PROTOCOL-FINDINGS §4d).
 */
@Composable
fun ModelBar(
    selection: ModelSelection,
    change: ModelChange,
    onSelect: (String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    var picking by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().background(colors.bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = KiroLayout.TouchTarget)
                .clickable(
                    onClickLabel = if (selection.hasCatalog) "Change model" else "About the model",
                    role = Role.Button,
                    onClick = { picking = true },
                )
                .padding(horizontal = KiroLayout.ScreenGutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("model", style = MaterialTheme.typography.labelMedium, color = colors.muted)

            Text(
                text = currentModelLabel(selection),
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelLarge,
                // Muted, not full strength, while unknown: the row must read as a
                // fact not yet in hand rather than as the name of a model.
                color = if (selection.isKnown) colors.textStrong else colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            RateBadge(selection.current)

            Box(Modifier.weight(1f))

            Text(
                text = if (change is ModelChange.InFlight) "switching…" else "change",
                style = MaterialTheme.typography.labelMedium,
                color = if (change is ModelChange.InFlight) colors.muted else colors.accent,
            )
        }

        (change as? ModelChange.Failed)?.let { failed ->
            ModelRefusal(failed, onDismissError)
        }
    }

    if (picking) {
        ModelPickerDialog(
            selection = selection,
            change = change,
            onSelect = { id ->
                picking = false
                onSelect(id)
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * The strip that names a refusal.
 *
 * Rendered beside the transcript, never in place of it: a rejected preference
 * leaves the session working on the model it already had, and a user who loses
 * their transcript over one has lost far more than the switch was worth.
 */
@Composable
private fun ModelRefusal(failed: ModelChange.Failed, onDismiss: () -> Unit) {
    val colors = KiroTheme.colors
    HairlineSurface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KiroLayout.ScreenGutter, vertical = 4.dp),
        background = colors.card,
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(3.dp).height(20.dp).background(colors.danger))
            Text(
                "Could not switch to ${failed.modelId}. ${failed.message}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.text,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun RateBadge(model: KiroModel?) {
    val colors = KiroTheme.colors
    val rate = formatRate(model?.rateMultiplier, model?.rateUnit) ?: return
    Text(
        rate,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(colors.bgElevated)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        // A model that bills above the baseline is called out in the warn role.
        // The figure itself is in the text, so colour only reinforces it -- the
        // meaning survives for anyone who cannot see the hue.
        color = if ((model?.rateMultiplier ?: 1.0) > 1.0) colors.warn else colors.muted,
    )
}

@Composable
private fun ModelPickerDialog(
    selection: ModelSelection,
    change: ModelChange,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (selection.hasCatalog) {
                    Text(
                        "Takes effect from your next message.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KiroTheme.colors.muted,
                    )
                    Box(Modifier.height(6.dp))
                    selection.available.forEach { model ->
                        ModelChoiceRow(
                            model = model,
                            selected = model.id == selection.currentId,
                            enabled = change !is ModelChange.InFlight,
                            onSelect = { onSelect(model.id) },
                        )
                    }
                } else {
                    // Not an error and not a retry: a cloud session's `session/new`
                    // carries no config options at all, and the catalogue arrives
                    // later over `config_option_update` (PROTOCOL-FINDINGS §4d).
                    // Offering a "Retry" here would invite the user to fix
                    // something that is merely still in the post.
                    Text(
                        "The sandbox sends its model list shortly after a session opens, " +
                            "and it has not arrived yet. It will appear here on its own — " +
                            "there is nothing to retry.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KiroTheme.colors.text,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ModelChoiceRow(
    model: KiroModel,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val colors = KiroTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            // Role.RadioButton so TalkBack announces "selected" rather than
            // leaving the current model indistinguishable from the rest.
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .background(if (selected) colors.accentSubtle else colors.bg)
            .heightIn(min = KiroLayout.TouchTarget)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.textStrong else colors.muted,
            )
            model.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.muted)
            }
        }
        RateBadge(model)
    }
}

/**
 * What to put on the bar for a session's current model.
 *
 * Three genuinely different answers, and none of them is a made-up default:
 * the model's display name; the bare wire id when the agent names a current
 * model it did not also list (so the id is all we honestly have); and an
 * explicit "not reported yet" before any `config_option_update` has landed.
 */
internal fun currentModelLabel(selection: ModelSelection): String =
    selection.current?.name ?: selection.currentId ?: "not reported yet"

/**
 * Renders a credit multiplier, or nothing at all.
 *
 * Null in, null out — an agent that omits `_meta.kiro.rateMultiplier` has told
 * us nothing about price, and printing "1×" or "free" there would be an
 * invention the user pays for. Formatted off [Double.toString] rather than a
 * `%f` pattern so a comma-decimal locale cannot turn 2.2× into 2,2×.
 */
internal fun formatRate(multiplier: Double?, unit: String?): String? {
    if (multiplier == null || !multiplier.isFinite()) return null
    val whole = multiplier.toLong()
    val number = if (whole.toDouble() == multiplier) {
        whole.toString()
    } else {
        multiplier.toString().trimEnd('0').trimEnd('.')
    }
    return buildString {
        append(number).append('×')
        if (!unit.isNullOrBlank()) append(' ').append(unit)
    }
}
