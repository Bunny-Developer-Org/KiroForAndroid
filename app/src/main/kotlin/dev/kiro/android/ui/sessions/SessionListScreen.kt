package dev.kiro.android.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.common.NamedState
import dev.kiro.android.ui.common.StatusDot
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.android.ui.theme.TabularNumbers
import dev.kiro.core.model.CloudSession
import dev.kiro.core.model.InstanceStatus
import dev.kiro.core.model.SessionStatus
import dev.kiro.core.session.ConnectionState

/**
 * Rows, not cards.
 *
 * Eleven stacked bordered cards is what "reach for a card less often on a phone"
 * is warning against — hairline dividers do the same job for a fraction of the
 * horizontal budget.
 */
@Composable
fun SessionListScreen(
    sessions: List<CloudSession>,
    pinnedIds: Set<String>,
    connection: ConnectionState,
    onOpen: (CloudSession) -> Unit,
    onNewSession: () -> Unit,
    onDelete: (CloudSession) -> Unit,
    onTogglePin: (CloudSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    // Confirmation is required (FEATURES F-10): held here, not in the caller, so
    // every entry point into delete goes through one dialog rather than each row
    // reimplementing the "are you sure" itself.
    var pendingDelete by remember { mutableStateOf<CloudSession?>(null) }

    Column(modifier.fillMaxSize().background(colors.bg)) {
        ConnectionBanner(connection)

        NewSessionAction(
            enabled = connection is ConnectionState.Connected &&
                connection.agentSupportsCloudSessions,
            connection = connection,
            onClick = onNewSession,
        )

        if (sessions.isEmpty()) {
            NamedState(
                title = "No cloud sessions yet",
                detail = "Start one and Kiro will clone your repository into its own " +
                    "sandbox, work there, and open a pull request when it is done.",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        pinned = session.id in pinnedIds,
                        onOpen = onOpen,
                        onTogglePin = { onTogglePin(session) },
                        onRequestDelete = { pendingDelete = session },
                    )
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
            }
        }
    }

    pendingDelete?.let { session ->
        DeleteSessionDialog(
            session = session,
            onConfirm = {
                onDelete(session)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * "Delete with confirmation" is the explicit acceptance bar (FEATURES F-10) —
 * this is the one dialog that satisfies it, named after what it does rather than
 * generically, since a session delete cannot be undone.
 */
@Composable
private fun DeleteSessionDialog(
    session: CloudSession,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = session.repositories.firstOrNull()?.name ?: session.title ?: session.id
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete session?") },
        text = { Text("\"$name\" will be removed. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = KiroTheme.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Creating is disabled **with a reason**, never just greyed out.
 *
 * ADR-005 §5.3 is explicit about this: a disabled control with no explanation is
 * indistinguishable from a broken one, and the usual reason here is simply that
 * the machine running the bridge is asleep.
 */
@Composable
private fun NewSessionAction(
    enabled: Boolean,
    connection: ConnectionState,
    onClick: () -> Unit,
) {
    val colors = KiroTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KiroLayout.ScreenGutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.accentFg,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = KiroLayout.TouchTarget),
        ) {
            Text("New cloud session", style = MaterialTheme.typography.labelLarge)
        }
        if (!enabled) {
            Text(
                when (connection) {
                    is ConnectionState.Unreachable ->
                        "You cannot start a session while the bridge is unreachable."
                    is ConnectionState.Connected ->
                        "This bridge's agent cannot place sessions in Kiro's cloud sandbox."
                    else -> "Connect to a bridge to start a session."
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: CloudSession,
    pinned: Boolean,
    onOpen: (CloudSession) -> Unit,
    onTogglePin: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = KiroTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(session) }
            .heightIn(min = KiroLayout.TouchTarget)
            .padding(horizontal = KiroLayout.ScreenGutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                color = if (session.status == SessionStatus.IN_PROGRESS) colors.ok else colors.muted,
                live = session.status == SessionStatus.IN_PROGRESS,
            )
            Box(Modifier.padding(end = 10.dp))
            Text(
                session.repositories.firstOrNull()?.name ?: session.title ?: session.id,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                session.updatedAt?.take(10).orEmpty(),
                style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                color = colors.muted,
            )

            // Compact -- the row is still one tap target for open; these two are
            // the only secondary affordances F-10 asks for (pin, delete-with-
            // confirmation), so a kebab menu would hide the state (pinned or not)
            // that the star is here to show at a glance.
            IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (pinned) "Unpin session" else "Pin session",
                    tint = if (pinned) colors.accent else colors.muted,
                )
            }
            IconButton(onClick = onRequestDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete session",
                    tint = colors.muted,
                )
            }
        }

        // The two statuses on their own line rather than competing with the repo
        // name -- an unbounded row of state chips starves the text column, which is
        // measurable at 390dp and total at 320dp.
        Text(
            statusLine(session),
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
        )
    }
}

/**
 * Says both statuses in words, because they mean different things.
 *
 * "Idle and warm" opens instantly; "idle but suspended" means waiting for a VM to
 * come back. Collapsing them into one word is the specific mistake F-10 calls out.
 */
private fun statusLine(session: CloudSession): String {
    val agent = when (session.status) {
        SessionStatus.IN_PROGRESS -> "working"
        SessionStatus.IDLE -> "idle"
        SessionStatus.UNKNOWN -> "status unknown"
    }
    val instance = when (session.instanceStatus) {
        InstanceStatus.SUSPENDED -> "sandbox suspended — takes a moment to wake"
        InstanceStatus.RUNNING -> "sandbox warm"
        InstanceStatus.NOT_APPLICABLE -> null
        InstanceStatus.UNKNOWN -> null
    }
    val mode = session.agentMode?.let { "· $it" }
    return listOfNotNull(agent, instance, mode).joinToString(" · ")
}

/**
 * The degradation contract, rendered.
 *
 * ADR-005 §5.3 makes this acceptance criteria rather than polish: every
 * bridge-unreachable path shows a **named state**, not a spinner, and says what
 * the user can do about it.
 */
@Composable
private fun ConnectionBanner(connection: ConnectionState) {
    val colors = KiroTheme.colors
    val (message, tone) = when (connection) {
        is ConnectionState.Connected ->
            if (connection.agentSupportsCloudSessions) {
                null to colors.ok
            } else {
                "This bridge is talking to a local-only agent, so cloud sessions " +
                    "are unavailable from it." to colors.warn
            }

        is ConnectionState.Unreachable -> {
            val seen = connection.lastSeenMillis?.let { relativeAge(it) }
            val base = "Bridge unreachable" + (seen?.let { " — last seen $it" } ?: "")
            val hint = if (connection.onlyBridgeIsWorkstation) {
                ". Your only bridge is a workstation, so it is probably asleep."
            } else {
                "."
            }
            (base + hint) to colors.warn
        }

        is ConnectionState.Rejected -> "Bridge refused the connection: ${connection.reason}" to colors.danger
        is ConnectionState.Reconnecting -> "Reconnecting (attempt ${connection.attempt})…" to colors.muted
        ConnectionState.Connecting -> "Connecting…" to colors.muted
        ConnectionState.Disconnected -> "Not connected to a bridge." to colors.muted
    }

    if (message == null) return

    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.bgElevated)
            .padding(horizontal = KiroLayout.ScreenGutter, vertical = 10.dp),
    ) {
        Row {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .background(tone)
                    .heightIn(min = 16.dp)
                    .width(3.dp),
            )
            Text(message, style = MaterialTheme.typography.labelMedium, color = colors.text)
        }
    }
}

private fun relativeAge(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}
