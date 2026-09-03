package dev.kiro.android.ui.transcript

import dev.kiro.core.model.KiroModel
import dev.kiro.core.model.ModelSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two pure functions behind the model bar.
 *
 * Split out of the composables on purpose: the app has no Compose test runtime
 * (no Robolectric, no `compose-ui-test`), and the decisions worth pinning here
 * are string decisions, not layout ones.
 */
class ModelBarTest {

    @Test
    fun `an absent multiplier prints nothing at all`() {
        // The load-bearing case. `_meta.kiro` is optional, and an agent that omits
        // it has said nothing about price -- so the bar must show no figure rather
        // than "1x" or "free" (PROTOCOL-FINDINGS §4d).
        assertNull(formatRate(null, "Credit"))
        assertNull(formatRate(null, null))
    }

    @Test
    fun `a fractional multiplier keeps its digits`() {
        assertEquals("0.1× Credit", formatRate(0.1, "Credit"))
        assertEquals("0.05× Credit", formatRate(0.05, "Credit"))
        assertEquals("2.2× Credit", formatRate(2.2, "Credit"))
        assertEquals("1.3× Credit", formatRate(1.3, "Credit"))
    }

    @Test
    fun `a whole multiplier loses its trailing zero`() {
        assertEquals("1× Credit", formatRate(1.0, "Credit"))
        assertEquals("3× Credit", formatRate(3.0, "Credit"))
    }

    @Test
    fun `a missing unit leaves the multiplier standing alone`() {
        assertEquals("2.2×", formatRate(2.2, null))
        assertEquals("2.2×", formatRate(2.2, "  "))
    }

    @Test
    fun `a non-finite multiplier is treated as no figure`() {
        assertNull(formatRate(Double.NaN, "Credit"))
        assertNull(formatRate(Double.POSITIVE_INFINITY, "Credit"))
    }

    @Test
    fun `an unknown selection is labelled as not reported rather than defaulted`() {
        // The state a cloud session opens in. It must not read as a model name --
        // inventing "Auto" here would be a claim the agent never made.
        assertEquals("not reported yet", currentModelLabel(ModelSelection.Unknown))
    }

    @Test
    fun `a known selection is labelled with the model's display name`() {
        val selection = ModelSelection(listOf(LUNA, OPUS), currentId = "gpt-5.6-luna")
        assertEquals("GPT 5.6 Luna", currentModelLabel(selection))
    }

    @Test
    fun `a current id outside the catalogue falls back to the bare wire id`() {
        // The agent named a model it did not list. The id is all we honestly have,
        // and showing it beats showing the first catalogue entry as if it were current.
        val selection = ModelSelection(listOf(LUNA, OPUS), currentId = "some-new-model")
        assertEquals("some-new-model", currentModelLabel(selection))
    }

    private companion object {
        val LUNA = KiroModel("gpt-5.6-luna", "GPT 5.6 Luna", "Experimental preview", 0.1, "Credit")
        val OPUS = KiroModel("claude-opus-5", "Claude Opus 5", "1M context window", 2.2, "Credit")
    }
}
