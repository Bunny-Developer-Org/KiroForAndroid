package dev.kiro.android.ui.bridges

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.core.auth.PairedBridge

/**
 * The bridge switcher.
 *
 * ADR-005 §5.2 makes bridges a *list*, not a single host, because sessions live
 * in the Kiro account rather than on any one machine -- so this screen is the
 * one place that fact becomes visible: pick which bridge to talk to, add
 * another, or retire one that is gone for good.
 */
@Composable
fun BridgeListScreen(
    bridges: List<PairedBridge>,
    activeBridgeId: String?,
    onSelect: (PairedBridge) -> Unit,
    onRemove: (PairedBridge) -> Unit,
    onAddBridge: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    var pendingRemoval by remember { mutableStateOf<PairedBridge?>(null) }

    Column(modifier.fillMaxSize().background(colors.bg)) {
        BridgeListTopBar(onBack = onBack, onAddBridge = onAddBridge)

        if (bridges.isEmpty()) {
            NamedState(
                title = "No bridges paired",
                detail = "Add a bridge to connect this phone to a machine running kiro-cli.",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(bridges, key = { it.id }) { bridge ->
                    BridgeRow(
                        bridge = bridge,
                        isActive = bridge.id == activeBridgeId,
                        onSelect = { onSelect(bridge) },
                        onRemove = { pendingRemoval = bridge },
                    )
                    HorizontalDivider(color = colors.border, thickness = 1.dp)
                }
            }
        }
    }

    // Removal drops a token from the Keystore and forgets the bridge entirely --
    // not reversible from here, so it gets the same confirm-before-destroy
    // treatment as any other unrecoverable action in this app.
    pendingRemoval?.let { bridge ->
        RemoveBridgeDialog(
            bridge = bridge,
            onConfirm = {
                onRemove(bridge)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@Composable
private fun BridgeListTopBar(onBack: () -> Unit, onAddBridge: () -> Unit) {
    val colors = KiroTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = KiroLayout.TouchTarget)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = colors.textStrong,
            )
        }
        Text(
            "Bridges",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = colors.textStrong,
        )
        IconButton(onClick = onAddBridge) {
            Icon(Icons.Filled.Add, contentDescription = "Add another bridge", tint = colors.accent)
        }
    }
}

@Composable
private fun BridgeRow(
    bridge: PairedBridge,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = KiroTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .heightIn(min = KiroLayout.TouchTarget)
            .padding(horizontal = KiroLayout.ScreenGutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bridge.displayName.ifBlank { bridge.url },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isActive) {
                    Box(Modifier.padding(start = 8.dp)) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Active bridge",
                            tint = colors.ok,
                            modifier = Modifier.heightIn(max = 16.dp),
                        )
                    }
                }
            }
            Text(
                bridge.url,
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                bridgeSubtitle(bridge),
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }

        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Remove bridge", tint = colors.danger)
        }
    }
}

private fun bridgeSubtitle(bridge: PairedBridge): String {
    val lastSeen = bridge.lastSeenMillis?.let { relativeAge(it) } ?: "never connected"
    val auth = when (bridge.authMode) {
        PairedBridge.AuthMode.CLI_LOGIN -> "signed in as you"
        PairedBridge.AuthMode.API_KEY -> "host API key"
        PairedBridge.AuthMode.UNKNOWN -> "auth mode unknown"
    }
    return "$lastSeen · $auth"
}

private fun relativeAge(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "last seen just now"
        minutes < 60 -> "last seen ${minutes}m ago"
        minutes < 60 * 24 -> "last seen ${minutes / 60}h ago"
        else -> "last seen ${minutes / (60 * 24)}d ago"
    }
}

@Composable
private fun RemoveBridgeDialog(
    bridge: PairedBridge,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this bridge?") },
        text = {
            Text(
                "This forgets \"${bridge.displayName.ifBlank { bridge.url }}\" and deletes its " +
                    "stored token. You will need to pair again to reconnect it.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = KiroTheme.colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
