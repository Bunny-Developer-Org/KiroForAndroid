package dev.kiro.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The app's colour roles.
 *
 * Material 3's `ColorScheme` has ~30 slots built around a tonal system; this
 * palette is hand-picked roles that are *not* tonally derived. Forcing one into
 * the other loses most of them, so the app carries both: read [KiroColors] in
 * your own composables, and let the derived `ColorScheme` feed the stock M3
 * components you did not write (VISUAL-LANGUAGE §2.1).
 *
 * A role that exists in one theme exists in both. `PaletteTest` enforces it.
 */
@Immutable
data class KiroColors(
    // surfaces — a four-step ramp of hand-picked near-blacks, not one colour at
    // four opacities and not a tonal palette generated from a seed
    val bg: Color,
    val bgAccent: Color,
    val bgElevated: Color,
    val bgHover: Color,
    val card: Color,
    val cardFg: Color,
    val cardHl: Color,
    val chrome: Color,
    // text
    val text: Color,
    val textStrong: Color,
    val muted: Color,
    val mutedStrong: Color,
    // lines — separation is hairlines, not shadows
    val border: Color,
    val borderStrong: Color,
    // the one high-chroma colour
    val accent: Color,
    val accentFg: Color,
    val accentHover: Color,
    val accentSubtle: Color,
    val accentGlow: Color,
    val ring: Color,
    // semantic roles
    val ok: Color,
    val okFg: Color,
    val warn: Color,
    val warnFg: Color,
    val danger: Color,
    val dangerFg: Color,
    val info: Color,
    val infoFg: Color,
    val aim: Color,
    val aimFg: Color,
    val clarify: Color,
    // diffs (F-17)
    val diffAdd: Color,
    val diffAddText: Color,
    val diffDel: Color,
    val diffDelText: Color,
    val diffHunk: Color,
    val diffHunkText: Color,
    val isDark: Boolean,
)

internal val DarkKiroColors = KiroColors(
    bg = Palette.DarkBg,
    bgAccent = Palette.DarkBgAccent,
    bgElevated = Palette.DarkBgElevated,
    bgHover = Palette.DarkBgHover,
    card = Palette.DarkCard,
    cardFg = Palette.DarkCardFg,
    cardHl = Palette.DarkCardHl,
    chrome = Palette.DarkChrome,
    text = Palette.DarkText,
    textStrong = Palette.DarkTextStrong,
    muted = Palette.DarkMuted,
    mutedStrong = Palette.DarkMutedStrong,
    border = Palette.DarkBorder,
    borderStrong = Palette.DarkBorderStrong,
    accent = Palette.DarkAccent,
    accentFg = Palette.DarkAccentFg,
    accentHover = Palette.DarkAccentHover,
    accentSubtle = Palette.DarkAccentSubtle,
    accentGlow = Palette.DarkAccentGlow,
    ring = Palette.DarkRing,
    ok = Palette.DarkOk,
    okFg = Palette.DarkOnStatus,
    warn = Palette.DarkWarn,
    warnFg = Palette.DarkOnStatus,
    danger = Palette.DarkDanger,
    dangerFg = Palette.DarkOnStatus,
    info = Palette.DarkInfo,
    infoFg = Palette.DarkOnStatus,
    aim = Palette.DarkAim,
    aimFg = Palette.DarkOnStatus,
    clarify = Palette.DarkClarify,
    diffAdd = Palette.DarkDiffAdd,
    diffAddText = Palette.DarkDiffAddText,
    diffDel = Palette.DarkDiffDel,
    diffDelText = Palette.DarkDiffDelText,
    diffHunk = Palette.DarkDiffHunk,
    diffHunkText = Palette.DarkDiffHunkText,
    isDark = true,
)

internal val LightKiroColors = KiroColors(
    bg = Palette.LightBg,
    bgAccent = Palette.LightBgAccent,
    bgElevated = Palette.LightBgElevated,
    bgHover = Palette.LightBgHover,
    card = Palette.LightCard,
    cardFg = Palette.LightCardFg,
    cardHl = Palette.LightCardHl,
    chrome = Palette.LightChrome,
    text = Palette.LightText,
    textStrong = Palette.LightTextStrong,
    muted = Palette.LightMuted,
    mutedStrong = Palette.LightMutedStrong,
    border = Palette.LightBorder,
    borderStrong = Palette.LightBorderStrong,
    accent = Palette.LightAccent,
    accentFg = Palette.LightAccentFg,
    accentHover = Palette.LightAccentHover,
    accentSubtle = Palette.LightAccentSubtle,
    accentGlow = Palette.LightAccentGlow,
    ring = Palette.LightRing,
    ok = Palette.LightOk,
    okFg = Palette.LightOnDark,
    warn = Palette.LightWarn,
    warnFg = Palette.LightOnLight,
    danger = Palette.LightDanger,
    dangerFg = Palette.LightOnLight,
    info = Palette.LightInfo,
    infoFg = Palette.LightOnDark,
    aim = Palette.LightAim,
    aimFg = Palette.LightOnLight,
    clarify = Palette.LightClarify,
    diffAdd = Palette.LightDiffAdd,
    diffAddText = Palette.LightDiffAddText,
    diffDel = Palette.LightDiffDel,
    diffDelText = Palette.LightDiffDelText,
    diffHunk = Palette.LightDiffHunk,
    diffHunkText = Palette.LightDiffHunkText,
    isDark = false,
)
