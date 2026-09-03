package dev.kiro.android.ui.transcript

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroMotion
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.android.ui.theme.LocalReduceMotion
import dev.kiro.core.model.UserInputRequest

/**
 * The agent's second human-in-the-loop channel: a free-text question
 * (`_kiro/userInput`), distinct from [ApprovalCard]'s fixed-option authorisation.
 *
 * Shares the accent-edge/card idiom with [ApprovalCard] but not its button
 * layout — there is nothing to pick from here, so the card is a question, an
 * answer field, and a submit action, with a separate way to dismiss without
 * answering.
 */
@Composable
fun UserInputCard(
    request: UserInputRequest,
    onSubmit: (answer: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val shape = MaterialTheme.shapes.small
    var answer by remember(request.toolCallId) { mutableStateOf("") }

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
            // The role edge: a clarifying question, not a warning -- a distinct
            // colour from ApprovalCard's warn edge so the two are not confused at
            // a glance. A sibling with a fixed width, not a matchParentSize()
            // overlay: matchParentSize() forces a child's minWidth to the parent's
            // full width once the parent's own width is fixed (fillMaxWidth() does
            // that here), so the trailing .width(3.dp) was silently clamped back up
            // to full width and flooded the whole card in the role colour.
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(colors.clarify),
            )

            Column(
                Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "The agent has a question",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textStrong,
                )
                Text(
                    request.question,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )

                AnswerField(
                    value = answer,
                    onValueChange = { answer = it },
                    placeholder = request.placeholder,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (answer.isNotBlank()) onSubmit(answer.trim()) },
                        enabled = answer.isNotBlank(),
                        modifier = Modifier
                            .sizeIn(minHeight = KiroLayout.TouchTarget)
                            .semantics { contentDescription = "Submit answer" },
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.accentFg,
                        ),
                    ) {
                        Text("Submit", style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .sizeIn(minHeight = KiroLayout.TouchTarget)
                            .semantics { contentDescription = "Dismiss without answering" },
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                    ) {
                        Text("Dismiss", style = MaterialTheme.typography.labelLarge, color = colors.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String?,
) {
    val colors = KiroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(colors.bgElevated)
            .border(1.dp, if (focused) colors.accent else colors.border, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder ?: "Type your answer…",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            interactionSource = interaction,
            textStyle = TextStyle(
                color = colors.text,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            ),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().sizeIn(maxHeight = 160.dp),
        )
    }
}
