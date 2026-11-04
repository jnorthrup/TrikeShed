package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlin.test.*

/** Syntax remains data; the only outer operation is the existing explicitly selected receipt query. */
class EnglishCopularPresetTest {
    @Test fun sharedFullGovernorQueryFitsTheExistingEightPatternGrammar() {
        val program = EnglishLcncPreset.copularPattern()
        val query = SparqlGraph.lower(program)
        assertTrue(program.controls.inspectionOnly)
        assertEquals(8, query.graph.patterns.size)
        assertEquals(_l["head", "subject", "copula", "subjectWord", "predicateWord"], query.variables)
        val patterns = program.nodes.view.filter { it.type == SparqlGraph.PATTERN }
        assertEquals(_l["?cop", "?cop", "?sub", "?sub", "?cop", "?sub", "?subject", "?head"], patterns.map { it.params["subject"] })
        assertEquals("?head", patterns[2].params["object"], "Bind the shared governor before filtering the second relation")
        assertEquals("\"nsubj\"", patterns[3].params["object"])
        assertTrue(query.text.contains("\"cop\"") && query.text.contains("\"nsubj\""))
        assertFalse(query.text.contains("subclass") || query.text.contains("instance"))
    }

    @Test fun offeredDiagramAndReceiptQueryRetainTypedWiringViewportAndCaveats() {
        for (name in s_[EnglishLcncPreset.COPULAR_NAME, EnglishLcncPreset.COPULAR_PATTERN_NAME].view) {
            val document = LcncPresets.all().getValue(name)
            val program = LcncProgramConfix.fromJson(name, document)
            assertEquals(document, LcncProgramConfix.toJson(program))
            assertNotNull(program.view); assertTrue(program.seq > 0)
            assertNotNull(LcncPresets.info(name))
            assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
        }
        val outer = EnglishLcncPreset.buildCopular("sha256:" + "a".repeat(64), "current")
        assertEquals(_s["note", LcncContracts.SCOPE_IN, "text.value", "json.value", EnglishObservationContract.TYPE,
            "display", LcncContracts.SCOPE_OUT], outer.nodes.view.map { it.type }.toSet())
        assertEquals(8, outer.wires.size)
        val words = outer.nodes.view.single { it.id == "instructions" }.params.getValue("text")
        assertTrue(words.contains("negated, modal and conditional"))
        assertTrue(words.contains("not the complete sentence"))
        assertTrue(words.contains("not additional source occurrences"))
        assertEquals("current", outer.nodes.view.single { it.id == "mode" }.params["value"])
    }

    @Test fun constructionNeverChoosesAnUnrequestedDatasetOrSourceMode() {
        val offered = EnglishLcncPreset.buildCopular()
        assertEquals("", offered.nodes.view.single { it.id == "receipt" }.params["default"])
        assertEquals("snapshot", offered.nodes.view.single { it.id == "mode" }.params["value"])
        assertFailsWith<IllegalArgumentException> { EnglishLcncPreset.buildCopular("sha256:short") }
        assertFailsWith<IllegalArgumentException> { EnglishLcncPreset.buildCopular(mode = "") }
        assertFailsWith<IllegalArgumentException> { EnglishLcncPreset.buildCopular(mode = "union") }
    }
}
