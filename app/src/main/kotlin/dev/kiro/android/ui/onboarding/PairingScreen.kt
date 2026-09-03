package dev.kiro.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme

/**
 * The first screen, and the one that has to tell the truth.
 *
 * ADR-005 §5.4 fixes the order and it is not arbitrary: **state the requirement
 * before asking for anything.** A user who gets to a pairing form before learning
 * that this app needs a machine they run has been misled, and they will find out
 * at the least convenient moment.
 *
 * The last step matters as much as the first — a user who pairs and signs in but
 * never connects a source provider lands on an empty repository picker with
 * nothing to explain why.
 */
@Composable
fun PairingScreen(
    onPair: (url: String, code: String) -> Unit,
    errorMessage: String?,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    var url by remember { mutableStateOf("ws://") }
    var code by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(KiroLayout.ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Connect a bridge",
            style = MaterialTheme.typography.titleLarge,
            color = colors.textStrong,
        )

        RequirementCopy()

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Bridge address") },
            placeholder = { Text("wss://bridge.example.com:8765/acp") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = { Text("Pairing code") },
            supportingText = { Text("Printed by the bridge when it starts. Valid for 5 minutes.") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (errorMessage != null) {
            Text(errorMessage, style = MaterialTheme.typography.bodyMedium, color = colors.danger)
        }

        Button(
            onClick = { onPair(url.trim(), code.trim()) },
            enabled = !busy && code.isNotBlank() && url.length > "ws://".length,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.accentFg,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = KiroLayout.TouchTarget),
        ) {
            Text(if (busy) "Pairing…" else "Pair", style = MaterialTheme.typography.labelLarge)
        }

        Text(
            "After pairing you will sign in to Kiro, then connect GitHub or GitLab in " +
                "Kiro's own settings. Without that last step the repository picker has " +
                "nothing to show.",
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
        )
    }
}

/**
 * The honest part, kept whole and in order.
 *
 * Extracted so the form below it stays readable, not to bury it: ADR-005 §5.4
 * puts these three paragraphs *before* the first input on purpose, and moving any
 * of them behind a "learn more" would defeat the point.
 */
@Composable
private fun RequirementCopy() {
    val colors = KiroTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "This app talks to your Kiro cloud sessions through a small program you " +
                "run yourself, called a bridge. Kiro publishes no API for third-party " +
                "clients, so there is no way around this — and no version of this app " +
                "that works without one.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.text,
        )

        Text(
            "The bridge needs the kiro-cli binary, a signed-in Kiro account on a Pro " +
                "plan or higher, and outbound internet. It does not need a copy of your " +
                "code or any git credentials — Kiro clones your repositories inside its " +
                "own sandbox.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )

        Text(
            "Run it somewhere that stays on. A laptop works, but the app is silent " +
                "while that laptop is asleep: notifications come from the bridge, so a " +
                "sleeping bridge means no approval prompts reach your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
    }
}
