package dev.kiro.android.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalKiroColors = staticCompositionLocalOf<KiroColors> { error("KiroTheme missing") }

/**
 * Whether the user has asked the system to reduce motion.
 *
 * Two settings on Android, not one — and when set, **delays must be zeroed as
 * well as durations**. A stagger built on `delayMillis` whose duration is zeroed
 * but whose delay is not leaves items fully invisible for the length of the
 * delay, which is the exact bug KiroCrew hit on the web (VISUAL-LANGUAGE §5).
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

object KiroTheme {
    val colors: KiroColors
        @Composable @ReadOnlyComposable
        get() = LocalKiroColors.current
}

@Composable
fun KiroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkKiroColors else LightKiroColors
    val resolver = LocalContext.current.contentResolver

    val reduceMotion = runCatching {
        val animator = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val transition = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        animator == 0f || transition == 0f
    }.getOrDefault(false)

    CompositionLocalProvider(
        LocalKiroColors provides colors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            // Note: no dynamicDarkColorScheme(). Material You would replace a
            // hand-tuned neutral ramp and a deliberate single accent with a palette
            // seeded from the user's wallpaper -- a different design, not a
            // personalised version of this one. A knowing departure from Android's
            // default advice (VISUAL-LANGUAGE §2.1).
            colorScheme = colors.toMaterialScheme(darkTheme),
            typography = KiroTypography,
            shapes = KiroShapes,
            content = content,
        )
    }
}

/**
 * A derived M3 scheme, so `Switch`, `TextField` and friends are not visually
 * stranded. **Read `KiroTheme.colors` in your own composables**; this exists only
 * for the components you did not write.
 */
private fun KiroColors.toMaterialScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = accent, onPrimary = accentFg,
        secondary = aim, onSecondary = aimFg,
        background = bg, onBackground = text,
        surface = card, onSurface = cardFg,
        surfaceVariant = bgElevated, onSurfaceVariant = muted,
        error = danger, onError = dangerFg,
        outline = border, outlineVariant = borderStrong,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = accentFg,
        secondary = aim, onSecondary = aimFg,
        background = bg, onBackground = text,
        surface = card, onSurface = cardFg,
        surfaceVariant = bgElevated, onSurfaceVariant = muted,
        error = danger, onError = dangerFg,
        outline = border, outlineVariant = borderStrong,
    )
}

/**
 * Radii, verbatim from KiroCrew and noticeably tighter than M3's defaults.
 *
 * **No pill-shaped buttons.** `RoundedCornerShape(50)` on a button is the single
 * fastest way to make a screen stop looking like this design.
 */
val KiroShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
)

/**
 * The type scale.
 *
 * Space Grotesk and JetBrains Mono are the specified faces and are **not yet
 * bundled** — the app falls back to the platform sans and mono until the OFL
 * files land in `res/font`. Everything else about the scale is per spec, and the
 * swap is one file when the fonts arrive.
 *
 * Sizes are `sp`, which makes them *design* sizes rather than rendered ones: a
 * user at 200% renders 14sp as 28sp, so every container must wrap or scroll
 * rather than clip.
 */
val KiroTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
)

/** Live-updating numbers must not jitter horizontally on every tick. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/**
 * Motion. One curve does most of the work, and panels arrive slower than they
 * leave — 420 in, 240 out. KiroCrew is explicit that these two are shared by
 * every sliding surface: move both or neither.
 */
object KiroMotion {
    val Ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val SheetIn = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    val SheetOut = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val ChipHop = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    const val SCALE_IN_MILLIS = 200
    const val RISE_MILLIS = 350
    const val SLIDE_UP_MILLIS = 300
    const val SHEET_IN_MILLIS = 420
    const val SHEET_OUT_MILLIS = 240

    /** A tool block appearing mid-stream should not snap. */
    const val TOOL_FADE_MILLIS = 600

    /** Caret blink, step-end. */
    const val CARET_BLINK_MILLIS = 1100

    /** Zeroes duration *and* delay. Passing only duration is the documented bug. */
    fun duration(base: Int, reduceMotion: Boolean): Int = if (reduceMotion) 0 else base
}

/** Layout constants that are fixed rather than tuned per screen. */
object KiroLayout {
    val ScreenGutter = 16.dp
    val WideScreenGutter = 24.dp
    val CardInset = 8.dp
    val CardInsetVertical = 20.dp

    /** Shorter than M3's 64dp default. */
    val TopBarHeight = 52.dp

    /** Transcript column cap on tablets and foldables. */
    val MaxMessageWidth = 820.dp
    val StatusDot = 8.dp
    val ToolBlockHeaderHeight = 36.dp

    /**
     * KiroCrew says 44, Android says 48. Use 48 — on a platform whose own
     * accessibility scanner audits at 48, shipping 44 means arguing with the
     * tooling on every build. The *grading* survives: a 16dp glyph inside a 48dp
     * target is correct, and is how the density is preserved.
     */
    val TouchTarget = 48.dp
}
