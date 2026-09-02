package dev.kiro.android.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme

/**
 * The prompt composer.
 *
 * Insets are handled as **padding, not size** — `imePadding()` plus
 * `navigationBarsPadding()` is what keeps the send button above the gesture bar
 * when the keyboard is up, and it is the direct analogue of the `dvh` problem the
 * web version has.
 *
 * The send button is `accent` with **black** content, 48dp, and squared off at
 * 8dp. Not a pill: a pill-shaped filled button is the fastest way to make this
 * screen stop looking like the rest of the app.
 */
@Composable
fun Composer(
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    turnActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    var text by remember { mutableStateOf("") }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Row(
        modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .background(colors.bg)
            .padding(horizontal = KiroLayout.ScreenGutter, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .background(colors.bgElevated)
                .border(
                    1.dp,
                    if (focused) colors.accent else colors.border,
                    MaterialTheme.shapes.small,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    // Says what steering means here: a message sent mid-turn does
                    // not cancel the turn, it redirects it.
                    if (turnActive) "Steer the agent…" else "Ask Kiro to do something…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                interactionSource = interaction,
                textStyle = TextStyle(
                    color = colors.text,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().sizeIn(maxHeight = 160.dp),
            )
        }

        if (turnActive) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(KiroLayout.TouchTarget),
            ) {
                Icon(
                    Icons.Filled.Close,
                    // Icon-only, so it needs a real label -- and "cancel" alone
                    // would be ambiguous between the turn and the session.
                    contentDescription = "Stop this turn",
                    tint = colors.muted,
                )
            }
        }

        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onSend(text.trim())
                    text = ""
                }
            },
            enabled = text.isNotBlank(),
            modifier = Modifier
                .size(KiroLayout.TouchTarget)
                .clip(MaterialTheme.shapes.small)
                .background(if (text.isNotBlank()) colors.accent else colors.bgHover),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank()) colors.accentFg else colors.muted,
            )
        }
    }
}
