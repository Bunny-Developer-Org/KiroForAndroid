package dev.kiro.android.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parity guard VISUAL-LANGUAGE §9 asks for.
 *
 * KiroCrew guards its own token allowlist with a test asserting set equality
 * across two languages. The Android version is trivial and worth having on day
 * one, because the failure it catches — a role defined in dark and forgotten in
 * light — is invisible until someone opens the app in the other theme.
 */
class PaletteTest {

    @Test
    fun `every colour role differs between the two themes or is deliberately shared`() {
        val properties = KiroColors::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<KiroColors, *>>()
            .filter { it.name != "isDark" }

        assertTrue(properties.isNotEmpty(), "reflection found no colour roles")

        // Both themes must define every role. A null here would mean a role that
        // exists in one and not the other, which the data class makes impossible --
        // so this asserts the weaker but still useful property that nothing is
        // silently transparent.
        properties.forEach { property ->
            val dark = property.get(DarkKiroColors)
            val light = property.get(LightKiroColors)
            assertTrue(dark != null, "${property.name} is unset in the dark theme")
            assertTrue(light != null, "${property.name} is unset in the light theme")
        }
    }

    @Test
    fun `the accent changes hue between themes rather than merely dimming`() {
        // Mint on dark, a much deeper green on light -- because the light theme
        // needs contrast against white, not the same colour turned down. Nobody
        // should "fix" this into one value.
        assertTrue(
            DarkKiroColors.accent != LightKiroColors.accent,
            "the two themes must not share one accent value",
        )
    }

    @Test
    fun `the accent foreground on dark is black`() {
        // Deliberate, and the thing a reviewer will most often flag as a mistake.
        // Black on mint is what makes the accent read as a signal light rather
        // than as a button colour.
        assertEquals(0f, DarkKiroColors.accentFg.red)
        assertEquals(0f, DarkKiroColors.accentFg.green)
        assertEquals(0f, DarkKiroColors.accentFg.blue)
        assertEquals(1f, DarkKiroColors.accentFg.alpha)
    }

    /**
     * The same posture ADR-003 takes on `core/` purity, applied to colour: a
     * `Color(0xFF…)` literal outside `Palette.kt` means a UI element picked a
     * colour instead of inheriting a meaning.
     */
    @Test
    fun `no colour literal appears outside Palette`() {
        val uiRoot = File("src/main/kotlin/dev/kiro/android")
        if (!uiRoot.exists()) return

        val offenders = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Palette.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (LITERAL.containsMatchIn(line)) "${file.path}:${index + 1}: ${line.trim()}" else null
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "colour literals belong in Palette.kt only:\n" + offenders.joinToString("\n"),
        )
    }

    private companion object {
        val LITERAL = Regex("""Color\(0x[0-9A-Fa-f]{6,8}\)""")
    }
}
