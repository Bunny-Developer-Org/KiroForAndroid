package dev.kiro.android.ui.transcript

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.kiro.android.ui.theme.KiroColors
import dev.kiro.android.ui.theme.KiroLayout
import dev.kiro.android.ui.theme.KiroTheme
import dev.kiro.core.session.PromptBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ContentResolver

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
    onSend: (text: String, image: PromptBlock.Image?) -> Unit,
    onCancel: () -> Unit,
    turnActive: Boolean,
    supportsImages: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KiroTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }

    // Held separately from each other: [pendingImage] is what actually goes over
    // the wire, [pendingPreview] is only ever decoded for the thumbnail. Losing
    // the bitmap decode (a corrupt or huge image) should not stop the block from
    // being sendable.
    var pendingImage by remember { mutableStateOf<PromptBlock.Image?>(null) }
    var pendingPreview by remember { mutableStateOf<Bitmap?>(null) }
    fun clearAttachment() {
        pendingImage = null
        pendingPreview = null
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { loadPickedImage(context.contentResolver, uri) }
            if (loaded != null) {
                pendingImage = loaded.first
                pendingPreview = loaded.second
            }
        }
    }

    val canSend = text.isNotBlank() || pendingImage != null

    Column(modifier.fillMaxWidth().imePadding().navigationBarsPadding().background(colors.bg)) {
        pendingPreview?.let { preview ->
            AttachedImagePreview(preview, colors, onRemove = ::clearAttachment)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KiroLayout.ScreenGutter, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (supportsImages) {
                AttachButton(colors) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }

            PromptTextField(
                text = text,
                onTextChange = { text = it },
                turnActive = turnActive,
                colors = colors,
                modifier = Modifier.weight(1f),
            )

            if (turnActive) {
                CancelButton(colors, onCancel)
            }

            SendButton(canSend, colors) {
                onSend(text.trim(), pendingImage)
                text = ""
                clearAttachment()
            }
        }
    }
}

/** Reads, decodes, and base64-encodes a picked image off the main thread. */
private fun loadPickedImage(resolver: ContentResolver, uri: Uri): Pair<PromptBlock.Image, Bitmap>? = runCatching {
    val mimeType = resolver.getType(uri) ?: "image/jpeg"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    PromptBlock.Image(mimeType, base64) to bitmap
}.getOrNull()

@Composable
private fun AttachedImagePreview(bitmap: Bitmap, colors: KiroColors, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KiroLayout.ScreenGutter, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Attached image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, colors.border, MaterialTheme.shapes.small),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(KiroLayout.TouchTarget)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove attached image", tint = colors.muted)
        }
    }
}

@Composable
private fun AttachButton(colors: KiroColors, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(KiroLayout.TouchTarget)) {
        Icon(
            Icons.Filled.Image,
            // Icon-only, and "attach" alone would not say what kind of attachment
            // -- this app only ever offers images.
            contentDescription = "Attach an image",
            tint = colors.muted,
        )
    }
}

@Composable
private fun CancelButton(colors: KiroColors, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(KiroLayout.TouchTarget)) {
        Icon(
            Icons.Filled.Close,
            // Icon-only, so it needs a real label -- and "cancel" alone would be
            // ambiguous between the turn and the session.
            contentDescription = "Stop this turn",
            tint = colors.muted,
        )
    }
}

@Composable
private fun SendButton(enabled: Boolean, colors: KiroColors, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(KiroLayout.TouchTarget)
            .clip(MaterialTheme.shapes.small)
            .background(if (enabled) colors.accent else colors.bgHover),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            tint = if (enabled) colors.accentFg else colors.muted,
        )
    }
}

@Composable
private fun PromptTextField(
    text: String,
    onTextChange: (String) -> Unit,
    turnActive: Boolean,
    colors: KiroColors,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Box(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(colors.bgElevated)
            .border(1.dp, if (focused) colors.accent else colors.border, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (text.isEmpty()) {
            Text(
                // Says what steering means here: a message sent mid-turn does not
                // cancel the turn, it redirects it.
                if (turnActive) "Steer the agent…" else "Ask Kiro to do something…",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            interactionSource = interaction,
            textStyle = TextStyle(color = colors.text, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth().sizeIn(maxHeight = 160.dp),
        )
    }
}
