package dev.kiro.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * **The only file in the app allowed to contain a `Color(0xFF…)` literal.**
 *
 * VISUAL-LANGUAGE §2.1 makes that a rule rather than a preference, and
 * `PaletteTest` enforces it the way CI enforces `core/` purity. Everywhere else
 * reads `KiroTheme.colors`, so a new UI element inherits a *meaning* rather than
 * picking a colour.
 *
 * Values transcribed from KiroCrew's token file. They are the starting point, not
 * an invitation to re-pick.
 */
internal object Palette {

    // -- Dark. The design was drawn here; light is its inversion. -------------
    val DarkBg = Color(0xFF12141A)
    val DarkBgAccent = Color(0xFF14161D)
    val DarkBgElevated = Color(0xFF1A1D25)
    val DarkBgHover = Color(0xFF262A35)
    val DarkCard = Color(0xFF181B22)
    val DarkCardFg = Color(0xFFF4F4F5)
    val DarkCardHl = Color(0x0DFFFFFF)
    val DarkChrome = Color(0xF212141A)
    val DarkText = Color(0xFFE4E4E7)
    val DarkTextStrong = Color(0xFFFAFAFA)
    val DarkMuted = Color(0xFF7F7F88)
    val DarkMutedStrong = Color(0xFF52525B)
    val DarkBorder = Color(0xFF27272A)
    val DarkBorderStrong = Color(0xFF3F3F46)

    /** Mint, with a **black** foreground. That is deliberate — see §10.4. */
    val DarkAccent = Color(0xFF00D492)
    val DarkAccentFg = Color(0xFF000000)
    val DarkAccentHover = Color(0xFF34D399)
    val DarkAccentSubtle = Color(0x33047558)
    val DarkAccentGlow = Color(0x59047558)
    val DarkRing = Color(0xFF10B981)

    val DarkOk = Color(0xFF22C55E)
    val DarkWarn = Color(0xFFEAB308)
    val DarkDanger = Color(0xFFEF4444)
    val DarkInfo = Color(0xFF0891B2)
    val DarkAim = Color(0xFFA78BFA)
    val DarkClarify = Color(0xFFEAB308)
    val DarkOnStatus = Color(0xFF000000)

    val DarkDiffAdd = Color(0x262EA043)
    val DarkDiffAddText = Color(0xFF7EE787)
    val DarkDiffDel = Color(0x26F85149)
    val DarkDiffDelText = Color(0xFFFFA198)
    val DarkDiffHunk = Color(0x33047558)
    val DarkDiffHunkText = Color(0xFF6EE7B7)

    // -- Light. Note the accent changes *hue*, not just brightness. -----------
    val LightBg = Color(0xFFFAFAFA)
    val LightBgAccent = Color(0xFFF5F5F5)
    val LightBgElevated = Color(0xFFFFFFFF)
    val LightBgHover = Color(0xFFF0F0F0)
    val LightCard = Color(0xFFFFFFFF)
    val LightCardFg = Color(0xFF18181B)
    val LightCardHl = Color(0x08000000)
    val LightChrome = Color(0xF2FAFAFA)
    val LightText = Color(0xFF3F3F46)
    val LightTextStrong = Color(0xFF18181B)
    val LightMuted = Color(0xFF71717A)
    val LightMutedStrong = Color(0xFF52525B)
    val LightBorder = Color(0xFFE4E4E7)
    val LightBorderStrong = Color(0xFFD4D4D8)

    /** Deeper than the dark mint, because white needs contrast, not a dimmer mint. */
    val LightAccent = Color(0xFF047558)
    val LightAccentFg = Color(0xFFFFFFFF)
    val LightAccentHover = Color(0xFF059669)
    val LightAccentSubtle = Color(0x1F047558)
    val LightAccentGlow = Color(0x33047558)
    val LightRing = Color(0xFF047558)

    val LightOk = Color(0xFF16A34A)
    val LightWarn = Color(0xFFA16207)
    val LightDanger = Color(0xFFDC2626)
    val LightInfo = Color(0xFF0891B2)
    val LightAim = Color(0xFF7C3AED)
    val LightClarify = Color(0xFFA16207)
    val LightOnDark = Color(0xFF000000)
    val LightOnLight = Color(0xFFFFFFFF)

    val LightDiffAdd = Color(0x1F16A34A)
    val LightDiffAddText = Color(0xFF1A7F37)
    val LightDiffDel = Color(0x1FDC2626)
    val LightDiffDelText = Color(0xFFCF222E)
    val LightDiffHunk = Color(0x1F047558)
    val LightDiffHunkText = Color(0xFF065F46)

    // -- Syntax. Theme-independent by design (§2.3). --------------------------
    val SyntaxKeyword = Color(0xFFC678DD)
    val SyntaxString = Color(0xFF98C379)
    val SyntaxNumber = Color(0xFFD19A66)
    val SyntaxTitle = Color(0xFF61AFEF)
    val SyntaxType = Color(0xFFE5C07B)
    val SyntaxMeta = Color(0xFFE06C75)
}
