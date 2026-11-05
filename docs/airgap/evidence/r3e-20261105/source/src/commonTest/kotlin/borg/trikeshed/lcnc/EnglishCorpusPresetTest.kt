package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlin.test.*

class EnglishCorpusPresetTest {
    @Test fun offeredCorpusSearchUsesTheExistingTypedOwnerAndExplicitDatasetInputs(): Unit {
        val encoded = LcncPresets.all().getValue(EnglishCorpusPreset.NAME)
        val program = LcncProgramConfix.fromJson(EnglishCorpusPreset.NAME, encoded)
        assertEquals(encoded, LcncProgramConfix.toJson(program))
        assertNotNull(LcncPresets.info(EnglishCorpusPreset.NAME)); assertNotNull(program.view)
        assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
        assertEquals(_s["note", "json.value", "text.value", EnglishCorpusContract.TYPE, "display", LcncContracts.SCOPE_OUT],
            program.nodes.view.map { it.type }.toSet())
        val contract = assertNotNull(LcncContracts.find(EnglishCorpusContract.TYPE))
        assertTrue("query?" in contract.inputs); assertEquals("json", contract.inputKinds["query"])
        assertEquals("json", contract.outputKinds["search"]); assertFalse(contract.isEffect); assertFalse(contract.isSink)
        assertEquals(LcncNodeKey.ENGLISH_CORPUS, LcncNodeKey.of(contract.type))
        assertTrue(program.wires.view.any { it.fromNode == "query" && it.toNode == "corpus" && it.toPort == "query?" })
        for (port in s_["search", "terms", "topics", "source", "receiptCid"].view)
            assertTrue(program.wires.view.any { it.fromNode == "corpus" && it.fromPort == port && it.toPort == "value" })
        val note = program.nodes.view.single { it.id == "instructions" }.params.getValue("text")
        assertTrue(note.contains("not evidence confidence") && note.contains("not independent evidence"))
        val id = "sha256:" + "a".repeat(64)
        val configured = EnglishCorpusPreset.build(_l[id], "current", _l["not", "audit"], 2)
        assertEquals(_l[id], reifyStrict(configured.nodes.view.single { it.id == "receipts" }.params.getValue("value")))
        assertEquals("current", configured.nodes.view.single { it.id == "mode" }.params["value"])
        assertEquals(_l["not", "audit"], (reifyStrict(configured.nodes.view.single { it.id == "query" }.params.getValue("value")) as Map<*, *>)["terms"])
        assertFailsWith<IllegalArgumentException> { EnglishCorpusPreset.build(_l["sha256:short"]) }
        assertFailsWith<IllegalArgumentException> { EnglishCorpusPreset.build(mode = "union") }
        assertFailsWith<IllegalArgumentException> { EnglishCorpusPreset.build(limit = 257) }
    }
}
