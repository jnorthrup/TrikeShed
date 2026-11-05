package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlin.test.*

class EnglishNalPresetTest {
    @Test fun offeredReportUsesTypedRetainedInputsAndExistingQuotationFocus() {
        val document = LcncPresets.all().getValue(EnglishNalPreset.NAME)
        val program = LcncProgramConfix.fromJson(EnglishNalPreset.NAME, document)
        assertEquals(document, LcncProgramConfix.toJson(program))
        assertNotNull(LcncPresets.info(EnglishNalPreset.NAME)); assertNotNull(program.view)
        assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
        assertEquals(_s["note", LcncContracts.SCOPE_IN, "text.value", EnglishNalContract.TYPE,
            "display", "confix.pickPath", LcncContracts.SCOPE_OUT], program.nodes.view.map { it.type }.toSet())
        val contract = assertNotNull(LcncContracts.find(EnglishNalContract.TYPE))
        assertEquals(_l["receiptCid", "mode"], contract.inputs)
        assertFalse(contract.isEffect); assertFalse(contract.isSink)
        assertNotNull(LcncNodeKey.of(EnglishNalContract.TYPE), "Palette nodes must have the normal invocation key")
        assertTrue(contract.outputKinds.values.none { it in _s["ProposedRules", "ReviewedRules", "ReviewedRelationships"] })
        assertEquals("0", program.nodes.view.single { it.id == "passage" }.params["path"])
        assertTrue(program.wires.view.any { it.fromNode == "evaluate" && it.fromPort == "passages" && it.toNode == "passage" && it.toPort == "json?" })
        assertTrue(program.wires.view.any { it.fromNode == "evaluate" && it.fromPort == "passages" && it.toNode == "passages-out" })
        val note = program.nodes.view.single { it.id == "instructions" }.params.getValue("text")
        assertTrue(note.contains("ONE source occurrence") && note.contains("not the complete proof basis"))
        assertTrue(note.contains("not independent units") && note.contains("No parser"))
    }

    @Test fun explicitModeAndReceiptDoNotChangeOtherOfferedEnglishConstructions() {
        val offered = EnglishNalPreset.build()
        assertEquals("", offered.nodes.view.single { it.id == "receipt" }.params["default"])
        assertEquals("snapshot", offered.nodes.view.single { it.id == "mode" }.params["value"])
        val current = EnglishNalPreset.build("sha256:" + "a".repeat(64), "current")
        assertEquals("current", current.nodes.view.single { it.id == "mode" }.params["value"])
        assertFailsWith<IllegalArgumentException> { EnglishNalPreset.build("sha256:short") }
        assertFailsWith<IllegalArgumentException> { EnglishNalPreset.build(mode = "") }
        assertFailsWith<IllegalArgumentException> { EnglishNalPreset.build(mode = "union") }
        val copular = EnglishLcncPreset.buildCopular()
        assertTrue(copular.nodes.view.none { it.type == EnglishNalContract.TYPE })
        assertFalse(offered.controls.inspectionOnly, "An offered executable inspection still needs an explicit Run")
    }
}
