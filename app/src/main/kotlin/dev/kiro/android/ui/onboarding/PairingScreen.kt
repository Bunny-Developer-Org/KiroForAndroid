package dev.kiro.android.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    scanAvailable: Boolean = false,
    scanned: ScannedPairing? = null,
    onScanRequested: () -> Unit = {},
    onScanConsumed: () -> Unit = {},
) {
    val colors = KiroTheme.colors
    // Saveable, not merely remembered: a rotation, a dark-mode toggle from quick
    // settings, or a font-size change recreates the Activity, and losing a
    // hand-typed bridge address to that is a small betrayal on the one screen a
    // user only ever visits while already slightly unsure of themselves.
    var url by rememberSaveable { mutableStateOf("ws://") }
    var code by rememberSaveable { mutableStateOf("") }

    // Keyed on the identity rather than the value: scanning the *same* code again
    // after a failure must re-fire, and two equal payloads would not.
    LaunchedEffect(scanned?.id) {
        val hit = scanned ?: return@LaunchedEffect
        url = hit.url
        code = hit.code
        // Scanning is the confirmation -- F-07 asks for one tap, not two. The
        // fields are filled first and deliberately, so that a pairing which fails
        // leaves a populated form to correct by hand rather than an empty one.
        // Deleting the line below turns this into scan-then-confirm.
        onPair(hit.url, hit.code)
        // Consume it, or this screen re-pairs the previous bridge the next time it
        // is composed: "add another bridge" builds a *fresh* PairingScreen, whose
        // LaunchedEffect would fire immediately on a scan the user made minutes ago
        // and silently pair the bridge they already have instead of the new one.
        onScanConsumed()
    }

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

        // After the requirement copy, never before it: ADR-005 §5.4 puts stating
        // the requirement ahead of asking for anything, and a Scan button asks.
        //
        // Hidden rather than disabled when there is no scanner, so the screen
        // becomes exactly the one that shipped on 2026-09-02. Manual entry is a
        // complete path, not a fallback, and a dead button suggests otherwise.
        if (scanAvailable) {
            ScanButton(onClick = onScanRequested, enabled = !busy)
            OrDivider()
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Bridge address") },
            placeholder = { Text("wss://bridge.example.com:8765/acp") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )

        PairingCodeField(code = code, onCodeChange = { code = it })

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
 * Monospaced and upper-cased as you type, because this is read off a screen and
 * copied by hand — the bridge's alphabet already excludes I, O, 0 and 1 for the
 * same reason.
 */
@Composable
private fun PairingCodeField(code: String, onCodeChange: (String) -> Unit) {
    OutlinedTextField(
        value = code,
        onValueChange = { onCodeChange(it.uppercase()) },
        label = { Text("Pairing code") },
        supportingText = {
            Text(
                "Printed by the bridge when it starts, or run `kiro-bridge pair` on " +
                    "the bridge host. Valid for 5 minutes.",
            )
        },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Outlined, not filled, and that is a rule rather than a preference.
 *
 * "Pair" below is already the one filled accent control on this screen, and
 * VISUAL-LANGUAGE §1.4 wants the accent used as a signal light rather than a
 * button colour — two competing filled buttons undo exactly that.
 */
@Composable
private fun ScanButton(onClick: () -> Unit, enabled: Boolean) {
    val colors = KiroTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = colors.accent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = KiroLayout.TouchTarget),
    ) {
        // No contentDescription: the button already carries a text label, and
        // labelling the glyph too makes TalkBack announce it twice (§8).
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Scan the bridge's QR code", style = MaterialTheme.typography.labelLarge)
    }
}

/** Says that the form below is an equal alternative, not a consolation prize. */
@Composable
private fun OrDivider() {
    val colors = KiroTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = colors.border)
        Text(
            "or enter it by hand",
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = colors.border)
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
