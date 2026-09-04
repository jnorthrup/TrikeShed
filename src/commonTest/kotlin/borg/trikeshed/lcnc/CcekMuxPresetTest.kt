package borg.trikeshed.lcnc

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.cursor.ColumnOverlay
import borg.trikeshed.cursor.OverlayRole
import borg.trikeshed.cursor.Provenance
import borg.trikeshed.graal.ConfixBlackboard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CcekMuxPresetTest {

    @Test
    fun testPresetCcekMuxTypeChecksCleanly() {
        val json = LcncPresets.all()["preset-ccek-mux"]
        assertNotNull(json, "preset-ccek-mux must be registered in LcncPresets.all()")
        val program = LcncProgramConfix.fromJson("preset-ccek-mux", json)
        val violations = LcncTypeCheck.check(program)
        assertTrue(
            violations.isEmpty(),
            "preset-ccek-mux must have zero type violations, but found:\n" +
                violations.joinToString("\n") { "  " + it.render() }
        )
    }

    @Test
    fun testConfixBlackboardMergeContext() {
        val bb = ConfixBlackboard.empty()
        val context = BlackboardContext(
            id = "ctx-hermes-1",
            columnOverlays = mapOf(
                0 to ColumnOverlay(
                    name = "prompt",
                    defaultRole = OverlayRole.OBSERVATION,
                    description = "input prompt text",
                ),
                1 to ColumnOverlay(
                    name = "signal",
                    defaultRole = OverlayRole.DERIVED,
                    description = "ccek signal output",
                )
            ),
            provenance = Provenance(
                source = "keymux-hermes",
                timestamp = 1700000000L,
                creator = "hermes-agent",
            ),
            tags = mapOf("model" to "hermes-3-llama-3.1-405b", "env" to "test"),
        )

        bb.merge(context)

        assertEquals("ctx-hermes-1", bb.get("context.id"))
        assertEquals("hermes-3-llama-3.1-405b", bb.get("tag.model"))
        assertEquals("test", bb.get("tag.env"))
        assertEquals("prompt", bb.get("column.0.name"))
        assertEquals("OBSERVATION", bb.get("column.0.role"))
        assertEquals("input prompt text", bb.get("column.0.description"))
        assertEquals("keymux-hermes", bb.get("context.provenance.source"))
        assertEquals(1700000000L, bb.get("context.provenance.timestamp"))
        assertEquals("hermes-agent", bb.get("context.provenance.creator"))
    }

    @Test
    fun testConfixBlackboardMergeOther() {
        val bb1 = ConfixBlackboard.empty()
        bb1.put("k1", "v1", "lang1")
        bb1.put("k2", "v2", "lang1")

        val bb2 = ConfixBlackboard.empty()
        bb2.put("k2", "v2-override", "lang2")
        bb2.put("k3", "v3", "lang2")

        bb1.merge(bb2)

        assertEquals("v1", bb1.get("k1"))
        assertEquals("v2-override", bb1.get("k2"))
        assertEquals("v3", bb1.get("k3"))
    }
}
