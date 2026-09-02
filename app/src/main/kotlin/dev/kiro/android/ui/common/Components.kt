package dev.kiro.android.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.android.ui.theme.LocalReduceMotion

/**
 * Separation is done with a hairline, not a shadow.
 *
 * M3's `Card` with tonal elevation tints the surface toward the primary hue,
 * which on this palette pushes cards green. So: explicit background, explicit
 * 1dp border, zero elevation (VISUAL-LANGUAGE §4).
 */
@Composable
fun HairlineSurface(
    modifier: Modifier = Modifier,
    background: Color = KiroTheme.colors.card,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(background)
            .border(1.dp, KiroTheme.colors.border, shape),
    ) { content() }
}

/**
 * The live-session dot.
 *
 * Under reduced motion it keeps its **colour** and loses its pulse — it is a
 * state indicator, not decoration, so removing it entirely would remove
 * information.
 */
@Composable
fun StatusDot(color: Color, live: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val alpha = if (live && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "dot-breathe")
        transition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
            label = "dot-alpha",
        ).value
    } else {
        1f
    }

    Box(
        modifier
            .size(KiroLayout.StatusDot)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
            // Decorative: the row already states the status in words, and announcing
            // an unlabelled dot to TalkBack adds noise without adding meaning.
            .clearAndSetSemantics { },
    )
}

/**
 * The instrument-block header strip.
 *
 * Mono, 12sp, muted, min 36dp, on a fill mixed halfway between `bgElevated` and
 * `bg`. This strip is what makes a tool call read as instrumentation rather than
 * as another message.
 */
@Composable
fun InstrumentHeader(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val colors = KiroTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = KiroLayout.ToolBlockHeaderHeight)
            .background(lerpSurface(colors.bgElevated, colors.bg))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.muted,
            maxLines = 1,
        )
        trailing()
    }
}

private fun lerpSurface(a: Color, b: Color): Color = Color(
    red = (a.red + b.red) / 2,
    green = (a.green + b.green) / 2,
    blue = (a.blue + b.blue) / 2,
    alpha = 1f,
)

/** A named, explained empty or error state — never a bare spinner. */
@Composable
fun NamedState(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier.padding(KiroLayout.ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = KiroTheme.colors.textStrong)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = KiroTheme.colors.muted)
        Box(Modifier.height(4.dp))
        action()
    }
}
