package dev.kiro.android.ui.transcript

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroMotion
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.android.ui.theme.LocalReduceMotion
import dev.kiro.core.model.PermissionOption
import dev.kiro.core.session.TranscriptReducer

/**
 * The highest-stakes surface in the app.
 *
 * Three things here are requirements rather than styling:
 *
 *  - **The 3dp left edge in the role colour** is the signature of this idiom.
 *  - **Approve and reject are 48dp targets that are not adjacent.** This is the
 *    one place where a mis-tap has consequences an undo cannot reach, so the
 *    layout puts space between them on purpose.
 *  - **Options are rendered as sent.** The four Kiro 2.19.2 offers are not a
 *    contract; the list is agent-supplied and keyed by `kind`. Hard-coding it
 *    means a new option silently vanishes from the UI.
 *
 * The card does not disappear after a decision — the transcript is a record.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalCard(
    approval: TranscriptReducer.PendingApproval,
    onRespond: (optionId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val shape = MaterialTheme.shapes.small

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(KiroMotion.duration(KiroMotion.SCALE_IN_MILLIS, reduceMotion))) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(
                    KiroMotion.duration(KiroMotion.SCALE_IN_MILLIS, reduceMotion),
                    easing = KiroMotion.Ease,
                ),
            ),
    ) {
        Row(
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(shape)
                .background(colors.card)
                .border(1.dp, colors.border, shape),
        ) {
            // The role edge: warn while pending. A sibling with a fixed width next
            // to the content, not an overlay -- Box's matchParentSize() forces a
            // matchParentSize child's *minWidth* to the parent's full width when
            // that parent's width is already fixed (as fillMaxWidth() does here),
            // so a trailing .width(3.dp) got silently clamped back up to full
            // width and flooded the whole card in the role colour instead of
            // drawing a 3dp edge.
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(colors.warn),
            )

            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "The agent needs your approval",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textStrong,
                )
                Text(
                    approval.question ?: "An action requires permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Allow options first, reject options after a deliberate gap, so
                    // the two never sit shoulder to shoulder under a thumb.
                    approval.options.filter { it.kind.isAllow }.forEach { option ->
                        ApprovalAction(option, primary = true, onRespond)
                    }
                    Box(Modifier.width(24.dp))
                    approval.options.filter { !it.kind.isAllow }.forEach { option ->
                        ApprovalAction(option, primary = false, onRespond)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalAction(
    option: PermissionOption,
    primary: Boolean,
    onRespond: (String) -> Unit,
) {
    val colors = KiroTheme.colors
    // Text labels, always. An icon alone cannot carry a state-changing action, and
    // this is the most consequential one in the app.
    val description = buildString {
        append(option.name)
        if (option.kind.isStanding) append(", applies to future requests too")
    }

    if (primary) {
        Button(
            onClick = { onRespond(option.optionId) },
            modifier = Modifier
                .heightIn(min = KiroLayout.TouchTarget)
                .semantics { contentDescription = description },
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.accentFg,
            ),
        ) {
            Text(option.name, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        // Text color alone isn't enough affordance for the one mis-tap-proof
        // action on this card — the border has to read as destructive too, not
        // fall back to the neutral M3 outline.
        val emphasis = if (option.kind.isReject) colors.danger else colors.text
        OutlinedButton(
            onClick = { onRespond(option.optionId) },
            modifier = Modifier
                .heightIn(min = KiroLayout.TouchTarget)
                .semantics { contentDescription = description },
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = emphasis),
            border = BorderStroke(1.dp, if (option.kind.isReject) colors.danger else colors.border),
        ) {
            Text(option.name, style = MaterialTheme.typography.labelLarge, color = emphasis)
        }
    }
}
