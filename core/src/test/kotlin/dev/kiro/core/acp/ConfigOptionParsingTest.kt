package dev.kiro.core.acp

import dev.kiro.core.model.ConfigOption
import dev.kiro.core.model.ModelSelection
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shapes the fixtures do *not* contain.
 *
 * A cloud session's `session/new` and `session/load` results carry no
 * `configOptions` at all, and the ones that do are free to add fields this build
 * has never seen. Both must parse to something, never throw — the tolerance rule
 * ADR-003 §3 makes a requirement rather than a nicety.
 */
class ConfigOptionParsingTest {

    @Test
    fun `a result with no configOptions is unknown rather than empty-and-confident`() {
        val cloudNewSessionResult = buildJsonObject { put("sessionId", "s-1") }
        val selection = ConfigOptionParser.parse(cloudNewSessionResult).modelSelection()

        assertEquals(ModelSelection.Unknown, selection)
        assertFalse(selection.isKnown, "a cloud session/new tells us nothing about models")
        assertFalse(selection.hasCatalog)
        assertNull(selection.current)
    }

    @Test
    fun `null and non-object inputs parse to nothing`() {
        assertTrue(ConfigOptionParser.parse(null).isEmpty())
        assertTrue(ConfigOptionParser.parse(JsonNull).isEmpty())
    }

    /** A model option with no `_meta.kiro` block: nameable, not priceable. */
    @Test
    fun `a model choice without rate metadata parses with no price rather than zero`() {
        val selection = ConfigOptionParser.parse(
            configOptions(
                option("model", "Model", "model", "claude-opus-5") {
                    add(
                        buildJsonObject {
                            put("value", "claude-opus-5")
                            put("name", "Claude Opus 5")
                        },
                    )
                },
            ),
        ).modelSelection()

        val model = selection.available.single()
        assertEquals("claude-opus-5", model.id)
        assertNull(model.rateMultiplier, "absent pricing must not read as free")
        assertNull(model.rateUnit)
    }

    /**
     * The current model is not always in the list — a session pinned to a model
     * the registry later dropped is exactly that case. The id must survive so the
     * UI can still name it.
     */
    @Test
    fun `a current model missing from the catalog keeps its id`() {
        val selection = ConfigOptionParser.parse(
            configOptions(
                option("model", "Model", "model", "retired-model") {
                    add(
                        buildJsonObject {
                            put("value", "auto")
                            put("name", "Auto")
                        },
                    )
                },
            ),
        ).modelSelection()

        assertEquals("retired-model", selection.currentId)
        assertNull(selection.current, "we can name it, we cannot describe it")
        assertTrue(selection.isKnown)
    }

    /** An option KAS never sent. Carried, not understood, never fatal. */
    @Test
    fun `an unrecognised config option is carried without disturbing the model one`() {
        val options = ConfigOptionParser.parse(
            configOptions(
                option("somethingNew", "Something New", null, "on") {
                    add(
                        buildJsonObject {
                            put("value", "on")
                            put("name", "On")
                        },
                    )
                },
                option("model", "Model", "model", "auto") {
                    add(
                        buildJsonObject {
                            put("value", "auto")
                            put("name", "Auto")
                        },
                    )
                },
            ),
        )

        assertEquals(2, options.size)
        assertEquals("auto", options.modelSelection().currentId)
    }

    /** Matching falls back to `category`, so a rename of `id` does not lose the picker. */
    @Test
    fun `the model option is found by category when the id is spelled differently`() {
        val selection = ConfigOptionParser.parse(
            configOptions(
                option("modelSelect", "Model", ConfigOption.MODEL, "auto") {
                    add(
                        buildJsonObject {
                            put("value", "auto")
                            put("name", "Auto")
                        },
                    )
                },
            ),
        ).modelSelection()

        assertEquals("auto", selection.currentId)
    }

    @Test
    fun `a choice with no value is dropped rather than sinking the whole option`() {
        val selection = ConfigOptionParser.parse(
            configOptions(
                option("model", "Model", "model", "auto") {
                    add(buildJsonObject { put("name", "Nameless") })
                    add(
                        buildJsonObject {
                            put("value", "auto")
                            put("name", "Auto")
                        },
                    )
                },
            ),
        ).modelSelection()

        assertEquals(listOf("auto"), selection.available.map { it.id })
    }

    private fun configOptions(vararg options: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("configOptions", buildJsonArray { options.forEach { add(it) } })
    }

    private fun option(
        id: String,
        name: String,
        category: String?,
        currentValue: String,
        choices: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
    ) = buildJsonObject {
        put("id", id)
        put("name", name)
        category?.let { put("category", it) }
        put("currentValue", currentValue)
        put("options", buildJsonArray(choices))
    }
}
