package dev.kiro.android.ui.transcript

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.common.HairlineSurface
import dev.kiro.android.ui.common.InstrumentHeader
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroMotion
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.android.ui.theme.LocalReduceMotion
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.ToolCall
import dev.kiro.core.model.TranscriptEntry
import dev.kiro.core.session.TranscriptReducer

/**
 * The screen that defines the app.
 *
 * Two structural decisions, both load-bearing rather than stylistic:
 *
 *  - **No chat bubbles.** Messages are full-width rows on the page ground with
 *    role carried by a small label and by colour. A bubble UI would read as a
 *    messaging app; this is a work log.
 *  - **The streaming message is not in the lazy list.** It renders below it, as
 *    its own node, and joins the list only at turn end. ADR-003 §3 makes this a
 *    design constraint, not an optimisation: a string growing 30–60×/second
 *    inside a virtualised list is the canonical way to make a transcript stutter.
 */
/**
 * The repo-visibility gap this closes: a session's repositories were only ever
 * shown as one name in the session list row. This header names all of them,
 * and turns "I want to work on this repo again" into a one-tap action rather
 * than requiring the user to retype the slug on the create screen.
 *
 * Never implies a *running* session's repos can change (ADR-004 §5) — the only
 * "switch" on offer is starting a fresh session pre-seeded with the same repos.
 */
@Composable
fun TranscriptHeader(
    session: CloudSession,
    onBack: () -> Unit,
    onNewSessionInRepo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = KiroLayout.ScreenGutter, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to sessions",
                    tint = colors.text,
                )
            }
            Text(
                session.title ?: session.repositories.firstOrNull()?.name ?: session.id,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = colors.textStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (session.repositories.isNotEmpty()) {
            Text(
                session.repositories.joinToString(", ") { it.name },
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "New session in this repo",
                modifier = Modifier
                    .heightIn(min = KiroLayout.TouchTarget)
                    .clickable(onClick = onNewSessionInRepo),
                style = MaterialTheme.typography.labelLarge,
                color = colors.accent,
            )
        }
    }
}

@Composable
fun TranscriptScreen(
    state: TranscriptReducer.State,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    val listState = rememberLazyListState()

    // Follow the tail while a turn is running. Deliberately keyed on entry count
    // rather than on the streaming text, so this does not run on every chunk.
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) listState.animateScrollToItem(state.entries.lastIndex)
    }

    Column(modifier.fillMaxSize().background(colors.bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = KiroLayout.ScreenGutter,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.entries, key = { it.id }) { entry ->
                TranscriptRow(entry, Modifier.widthIn(max = KiroLayout.MaxMessageWidth))
            }
        }

        if (state.isStreaming) {
            StreamingMessage(
                text = state.streamingText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KiroLayout.ScreenGutter, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TranscriptRow(entry: TranscriptEntry, modifier: Modifier = Modifier) {
    when (entry) {
        // The agent is the page; the user is the interjection. Hence the user's own
        // turns get a card and a border while the agent's sit on the ground.
        is TranscriptEntry.UserMessage -> HairlineSurface(modifier.fillMaxWidth()) {
            Text(
                entry.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = KiroTheme.colors.cardFg,
            )
        }

        is TranscriptEntry.AgentMessage -> Text(
            entry.text,
            modifier = modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = KiroTheme.colors.text,
        )

        is TranscriptEntry.ToolCallEntry -> ToolBlock(entry.toolCall, modifier)

        is TranscriptEntry.Error -> HairlineSurface(
            modifier.fillMaxWidth(),
            background = KiroTheme.colors.card,
        ) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(3.dp).height(20.dp).background(KiroTheme.colors.danger))
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KiroTheme.colors.text,
                )
            }
        }

        is TranscriptEntry.TurnSummary -> Text(
            buildString {
                entry.credits?.let { append("%.3f".format(it)) }
                entry.unit?.let { append(" ").append(it).append("s") }
                entry.elapsedMillis?.let { append("  ·  ").append(it / 1000).append("s") }
                if (entry.usedTools.isNotEmpty()) {
                    append("  ·  ").append(entry.usedTools.joinToString(", "))
                }
            }.trim(),
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            color = KiroTheme.colors.muted,
        )

        // A protocol addition must be a cosmetic gap, never a hole. ADR-003 §3
        // requires the tolerance; this is what the tolerance looks like.
        is TranscriptEntry.Unknown -> HairlineSurface(
            modifier.fillMaxWidth(),
            background = KiroTheme.colors.bgElevated,
        ) {
            InstrumentHeader("unsupported update · ${entry.kind}")
        }
    }
}

@Composable
private fun ToolBlock(toolCall: ToolCall, modifier: Modifier = Modifier) {
    val colors = KiroTheme.colors
    HairlineSurface(
        modifier.fillMaxWidth(),
        background = colors.bgElevated,
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            InstrumentHeader(
                label = toolCall.rawInput["command"] ?: toolCall.title ?: toolCall.toolCallId,
            ) {
                Text(
                    toolCall.status.name.lowercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (toolCall.status) {
                        ToolCall.Status.COMPLETED -> colors.ok
                        ToolCall.Status.FAILED -> colors.danger
                        ToolCall.Status.IN_PROGRESS -> colors.accent
                        else -> colors.muted
                    },
                )
            }
            if (toolCall.output.isNotEmpty()) {
                Text(
                    toolCall.output.joinToString("\n").take(MAX_OUTPUT_CHARS),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text,
                )
            }
        }
    }
}

/**
 * The in-flight message.
 *
 * **No animation on this node while it streams** beyond the caret's opacity. It
 * is already the hottest node in the app; adding a shimmer or a fade would
 * reintroduce per-frame recomposition exactly where it costs most.
 *
 * The live region announces politely and only here — announcing every token would
 * be worse than announcing nothing.
 */
@Composable
private fun StreamingMessage(text: String, modifier: Modifier = Modifier) {
    val colors = KiroTheme.colors
    Row(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
        )
        Caret(colors.accent)
    }
}

@Composable
private fun Caret(color: Color) {
    val reduceMotion = LocalReduceMotion.current
    // Under reduced motion the caret becomes static at 90% rather than
    // disappearing -- it is a state indicator, and its absence would read as
    // "finished".
    val alpha = if (reduceMotion) {
        0.9f
    } else {
        rememberInfiniteTransition(label = "caret").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = KiroMotion.CARET_BLINK_MILLIS
                    1f at 0 using LinearEasing
                    1f at KiroMotion.CARET_BLINK_MILLIS / 2
                    0f at KiroMotion.CARET_BLINK_MILLIS / 2
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "caret-alpha",
        ).value
    }

    Box(
        Modifier
            .padding(start = 2.dp, bottom = 2.dp)
            .size(width = 2.dp, height = 16.dp)
            .alpha(alpha)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color),
    )
}

private const val MAX_OUTPUT_CHARS = 4000
