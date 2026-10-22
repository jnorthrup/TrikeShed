package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.*
import kotlin.test.*

class WorkspaceFacetsTest {
    @Test fun retainedProposalReviewRequiresAnExplicitQuestionAndBoardEffect() {
        val name = "preset-document-proposal-review"
        val source = assertNotNull(LcncWorkspacePresets.documents()[name], "Retained AI output needs a review handoff")
        val program = LcncProgramConfix.fromJson(name, source)
        assertTrue(program.controls.humanOversight)
        assertEquals("", program.nodes.view.single { it.id == "question" }.params["value"])
        assertEquals(listOf("document.reviewTask"), program.nodes.view.map { it.type }
            .filter { LcncFacts.of(LcncContracts.all()).isEffect(it) })
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        assertEquals(1, program.wires.view.count { it.fromNode == "question" && it.toNode == "task" && it.toPort == "question" })
    }

    val source = """{"nodes":[{"id":"雪","params":{"text":"a\\b","value":null}}],"wires":[]}"""

    fun inspections(bytes: ByteArray, syntax: Syntax, path: Series<Any> = emptySeriesOf(),
                    limit: Int = 32): Series<Map<String, Any?>> {
        val persisted = WorkspaceFacets.inspect(confixDoc(bytes, syntax), syntax, path, limit = limit)
        return if (syntax == Syntax.JSON) s_[persisted, WorkspaceFacets.inspect(bytes.decodeToString(), path, limit = limit)]
        else s_[persisted]
    }

    @Test fun workspaceBudgetIsExplicitAndGenericDefaultsRemainUnchanged() {
        assertEquals(ValueBudget(maxNodes = 65_536, maxChars = 1_048_576, maxDepth = 64, maxMillis = 250),
            WorkspaceFacets.valueBudget())
        assertEquals(ValueBudget(maxNodes = 8192, maxChars = 131072, maxDepth = 24, maxMillis = 50), ValueBudget())
        assertEquals(65_536, WorkspaceFacets.MAX_TOKENS)
        assertEquals(16_384, WorkspaceFacets.MAX_VALUE_BYTES)
        assertEquals("work_limit", WorkspaceFacets.valueBudget().violation(List(65_536) { null }))
        var nested: Any? = null
        repeat(65) { nested = listOf(nested) }
        assertEquals("depth_limit", WorkspaceFacets.valueBudget().violation(nested))
    }

    @Test fun fullShakeConstructionHasAnOmittedRootAndNavigableSmallValue() {
        val program = LcncShakeDemo.build().program
        val source = LcncProgramConfix.toJson(program)
        val json = source.encodeToByteArray()
        for (syntax in listOf(Syntax.JSON, Syntax.CBOR)) {
            val bytes = if (syntax == Syntax.JSON) json else CanonicalCbor.encode(confixDoc(json, Syntax.JSON))
            assertTrue(bytes.size in 16_385..WorkspaceFacets.MAX_BYTES)
            for (root in inspections(bytes, syntax, limit = 2).view) {
                assertEquals(true, root["found"])
                assertEquals(true, root["valueOmitted"])
                assertEquals(bytes.size, root["valueBytes"])
                assertEquals(16_384, root["valueLimit"])
                assertNull(root["value"])
                assertTrue((root["tokens"] as Int) in 8193..65_536)
                assertEquals(2, (root["rows"] as List<*>).size)
                assertEquals(2, root["next"])
                val cursor = root["cursor"] as Map<*, *>
                assertEquals(1, cursor["rowCount"])
                assertEquals(1, (cursor["rows"] as List<*>).size)
            }
            for (selected in inspections(bytes, syntax, s_["nodes", 0, "params"]).view) {
                assertEquals(true, selected["found"])
                assertEquals(false, selected["valueOmitted"])
                assertEquals(program.nodes[0].params, selected["value"])
                assertTrue((selected["valueBytes"] as Int) in 1..16_384)
            }
        }
    }

    @Test fun selectedPreviewLimitUsesUtf8SourceBytesForJsonAndCbor() {
        for (syntax in listOf(Syntax.JSON, Syntax.CBOR)) {
            // JSON quotes occupy two bytes; CBOR's text header occupies three at this length.
            val boundary = "雪".repeat(5460) + if (syntax == Syntax.JSON) "ab" else "a"
            for (extra in 0..1) {
                val value = boundary + "x".repeat(extra)
                val fields = mapOf("value" to value)
                val bytes = if (syntax == Syntax.JSON) jsonOf(fields).encodeToByteArray() else CanonicalCbor.encodeMap(fields)
                for (selected in inspections(bytes, syntax, s_["value"]).view) {
                    assertEquals(true, selected["found"])
                    assertEquals(extra > 0, selected["valueOmitted"])
                    assertEquals(16_384 + extra, selected["valueBytes"])
                    assertEquals(16_384, selected["valueLimit"])
                    assertEquals(if (extra == 0) value else null, selected["value"])
                }
            }
        }
    }

    @Test fun missingAndNullPreviewsRetainTheirPresenceAndByteCounts() {
        for (syntax in listOf(Syntax.JSON, Syntax.CBOR)) {
            val bytes = if (syntax == Syntax.JSON) """{"value":null}""".encodeToByteArray()
                else CanonicalCbor.encodeMap(mapOf("value" to null))
            for (key in listOf("value", "missing")) for (selected in inspections(bytes, syntax, s_[key]).view) {
                assertEquals(key == "value", selected["found"])
                assertEquals(false, selected["valueOmitted"])
                assertEquals(if (key == "missing") 0 else if (syntax == Syntax.JSON) 4 else 1, selected["valueBytes"])
                assertEquals(16_384, selected["valueLimit"])
                assertNull(selected["value"])
            }
        }
    }

    @Test fun excessiveFlatTokensAreRejectedOnStringAndPersistedPaths() {
        val source = "[" + "null,".repeat(65_535) + "null]"
        assertTrue(source.length < WorkspaceFacets.MAX_BYTES)
        assertEquals("work_limit", assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source) }.message)
        val json = confixDoc(source.encodeToByteArray(), Syntax.JSON)
        for (syntax in listOf(Syntax.JSON, Syntax.CBOR)) {
            val doc = if (syntax == Syntax.JSON) json else confixDoc(CanonicalCbor.encode(json), syntax)
            assertEquals(65_537, doc.index.facet(ConfixIndexK.Spans).size)
            assertEquals("work_limit", assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(doc, syntax) }.message)
        }
    }

    @Test fun depthSixtyFourIsAcceptedAndSixtyFiveRejected() {
        for (depth in 64..65) {
            val source = "[".repeat(depth) + "null" + "]".repeat(depth)
            if (depth == 64) assertEquals(depth, WorkspaceFacets.inspect(source)["maxDepth"])
            else assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source) }
            for (syntax in listOf(Syntax.JSON, Syntax.CBOR)) {
                val bytes = if (syntax == Syntax.JSON) source.encodeToByteArray()
                    else ByteArray(depth + 1) { if (it == depth) 0xf6.toByte() else 0x81.toByte() }
                val doc = confixDoc(bytes, syntax)
                if (depth == 64) assertEquals(depth, WorkspaceFacets.inspect(doc, syntax)["maxDepth"])
                else assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(doc, syntax) }
            }
        }
    }

    @Test fun cborValuesRemainRepresentableAtTheJsonBoundary() {
        val bytes = borg.trikeshed.job.CanonicalCbor.encodeMap(mapOf(
            "binary" to byteArrayOf(0, 127, -1), "nonfinite" to Double.POSITIVE_INFINITY, "value" to "医\nquoted"))
        val doc = borg.trikeshed.parse.confix.confixDoc(bytes, borg.trikeshed.parse.confix.Syntax.CBOR)
        val inspected = WorkspaceFacets.inspect(doc, borg.trikeshed.parse.confix.Syntax.CBOR)
        val value = (reifyStrict(jsonOf(inspected)) as Map<*, *>)["value"] as Map<*, *>
        assertEquals(mapOf("type" to "bytes", "value" to listOf(0.0, 127.0, 255.0)), value["binary"])
        assertEquals(mapOf("type" to "float", "value" to "Infinity"), value["nonfinite"])
        assertEquals("医\nquoted", value["value"])
    }

    @Test fun selectedContainerIsDataAndSpansRetainUtf8ByteCoordinates() {
        val result = WorkspaceFacets.inspect(source, s_["nodes", 0, "params"])
        assertEquals(ContentId.of(source.encodeToByteArray()).value, result["cid"])
        assertEquals(mapOf("text" to "a\\b", "value" to null), result["value"])
        assertEquals(true, result["found"])
        val root = (result["rows"] as List<*>)[0] as Map<*, *>
        assertEquals(0, root["open"])
        assertEquals(source.encodeToByteArray().lastIndex, root["close"])
        assertEquals(result["value"], (reify(jsonOf(result)) as Map<*, *>)["value"])
    }

    @Test fun nullAndMissingAreDistinctAndWindowsAreBounded() {
        assertEquals(true, WorkspaceFacets.inspect(source, s_["nodes", 0, "params", "value"])["found"])
        assertEquals(false, WorkspaceFacets.inspect(source, s_["missing"])["found"])
        val page = WorkspaceFacets.inspect(source, limit = 2)
        assertEquals(2, (page["rows"] as List<*>).size)
        assertEquals(2, page["next"])
        val next = WorkspaceFacets.inspect(source, offset = 2, limit = 2)
        assertEquals(2, ((next["rows"] as List<*>)[0] as Map<*, *>)["token"])
        assertTrue((WorkspaceFacets.inspect(source, offset = Int.MAX_VALUE)["rows"] as List<*>).isEmpty())
    }

    @Test fun invalidPathsAndBudgetsAreRefused() {
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source, s_[-1]) }
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source, limit = 129) }
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source, offset = -1) }
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(" ".repeat(WorkspaceFacets.MAX_BYTES + 1)) }
    }

    @Test fun cursorIndexesPreserveHierarchyAndComposeSimultaneousSelections() {
        val tree = """[[1,"a",2],[3,4]]"""
        fun inspect(query: CursorFacetQuery) = WorkspaceFacets.inspect(tree, limit = 2, cursor = query)["cursor"] as Map<*, *>
        val axes = s_[intArrayOf(2), intArrayOf(0, 1)]
        val root = inspect(CursorFacetQuery(axes = axes))
        assertEquals(1, root["rowCount"]) // Roots only, never the flat token stream.
        val branch = inspect(CursorFacetQuery(path = s_[0, 0], axes = axes))
        assertEquals(3, branch["rowCount"])
        assertEquals(2, branch["next"])
        val indexes = branch["indexes"] as List<*>
        assertEquals(2, (indexes[0] as Map<*, *>)["groupCount"])
        assertEquals(3, (indexes[1] as Map<*, *>)["groupCount"])
        val numbers = inspect(CursorFacetQuery(path = s_[0, 0], axes = axes, selections = s_[0 j 0]))
        assertEquals(listOf(0, 2), (numbers["rows"] as List<*>).map { (it as Map<*, *>)["ordinal"] })
        val both = inspect(CursorFacetQuery(path = s_[0, 0], axes = axes, selections = s_[0 j 0, 1 j 2], groupOffset = 2))
        assertEquals(1, both["selectedCount"])
        assertEquals(2, ((both["rows"] as List<*>).single() as Map<*, *>)["ordinal"])
        val sibling = inspect(CursorFacetQuery(path = s_[0, 1], axes = axes))
        assertEquals(2, sibling["rowCount"])
        assertEquals(listOf(0, 1), (sibling["rows"] as List<*>).map { (it as Map<*, *>)["ordinal"] })
    }

    @Test fun cursorScopeVersionAndBoundsAreChecked() {
        val cid = ContentId.of(source.encodeToByteArray()).value
        assertEquals(cid, WorkspaceFacets.inspect(source, cursor = CursorFacetQuery(sourceCid = cid))["cid"])
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source, cursor = CursorFacetQuery(sourceCid = "old")) }
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source, cursor = CursorFacetQuery(path = s_[1])) }
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source, cursor = CursorFacetQuery(axes = s_[intArrayOf(3)])) }
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source, cursor = CursorFacetQuery(selections = s_[0 j 10])) }
        assertFailsWith<IllegalArgumentException> { WorkspaceFacets.inspect(source, cursor = CursorFacetQuery(offset = -1)) }
        val empty = WorkspaceFacets.inspect("[]", cursor = CursorFacetQuery(path = s_[0]))["cursor"] as Map<*, *>
        assertEquals(0, empty["rowCount"])
        assertTrue((empty["rows"] as List<*>).isEmpty())
    }

    @Test fun retainedDeliberationIsAnExplicitModelConstructionWithoutAdmissionOrActuation() {
        val name = "preset-document-deliberation"
        val doc = assertNotNull(LcncWorkspacePresets.documents()[name], "The retained evidence needs an authored council construction")
        val program = LcncProgramConfix.fromJson(name, doc)
        assertTrue(program.controls.humanOversight)
        assertTrue(LcncTypeCheck.check(program).isEmpty(), LcncTypeCheck.check(program).joinToString { it.render() })
        val seats = program.nodes.view.filter { it.type == "council.seat" }
        assertEquals(listOf("party-a", "party-b", "reviewer"), seats.map { it.id })
        val facts = LcncFacts.of(LcncContracts.all())
        assertTrue(facts.isEffect("council.seat"), "A model request is an effect, not a pure transform")
        assertEquals(setOf("council.seat"), program.nodes.view.filter { facts.isEffect(it.type) }.map { it.type }.toSet())
        assertTrue(seats.view.all { it.params.getValue("system").contains("not observations or votes") })
        assertTrue(program.wires.view.any { it.fromNode == "sheet" && it.toNode == "evidence-prompt" && it.fromPort == "sheet" })
        assertTrue(program.wires.view.any { it.fromNode == "simulation" && it.toNode == "simulation-prompt" && it.fromPort == "evaluation" })
        assertTrue(program.wires.view.none { it.toNode == "simulation" && it.fromNode in seats.map { s -> s.id } })
        for (prompt in program.nodes.view.filter { it.type == PromptNodes.RENDER }.view) {
            assertTrue(program.wires.view.any { it.toNode == prompt.id && it.toPort == "template" })
        }
        val unwired = LcncProgram("unwired-seat", s_[
            LcncNode("text", "text.value", mapOf("value" to "review"), x = 100.0, y = 100.0),
            LcncNode("seat", "council.seat", x = 200.0, y = 100.0),
        ], emptySeriesOf())
        assertTrue(LcncTreeShake.shake(unwired).made.isEmpty(), "Tree shake must not auto-connect a model effect")
    }

    @Test fun retainedSimulationPinsEvidenceAndRequiresExplicitAssumptions() {
        val assertions = listOf(mapOf("subject" to "Delays", "obj" to "reviewer"))
        val program = LcncWorkspacePresets.simulation("sha256:retained", assertions)
        assertTrue(program.controls.humanOversight)
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        assertEquals(setOf("document.sheet", "sheet.concentric", "json.value", "scope", "pick", "nars.reteFire", "display", "note"),
            program.nodes.view.map { it.type }.toSet())
        val sheet = program.nodes.view.single { it.type == "document.sheet" }
        assertEquals("sha256:retained", sheet.params["receiptCid"])
        assertEquals("rules", sheet.params["view"])
        assertEquals("32", sheet.params["limit"])
        assertEquals(assertions, reify(program.nodes.view.single { it.id == "assumptions" }.params.getValue("value")))
        assertEquals(emptyList<Any?>(), reify(LcncWorkspacePresets.simulation().nodes.view
            .single { it.id == "assumptions" }.params.getValue("value")))
        // Proposed rules reach Rete only through the explicit record projection, never directly from the sheet.
        assertTrue(program.wires.view.any { it.fromNode == "sheet" && it.fromPort == "ruleCandidates" && it.toNode == "rules" && it.toPort == "rules" })
        assertTrue(program.wires.view.any { it.fromNode == "rule-values" && it.toNode == "simulation" && it.toPort == "rules?" })
        assertTrue(program.wires.view.none { it.fromNode == "sheet" && it.toNode == "simulation" })
        assertTrue(program.nodes.view.single { it.type == "note" }.params.getValue("text").contains("Only the selected rules page"))
    }

    @Test fun domainPresetsReuseCuratorWithoutAutomaticRuleAdmission() {
        for ((name, json) in LcncWorkspacePresets.documents()) {
            val p = LcncProgramConfix.fromJson(name, json)
            assertTrue(LcncTypeCheck.check(p).isEmpty(), "$name: ${LcncTypeCheck.check(p)}")
            if (name in setOf(LcncWorkspacePresets.MEDICAL, LcncWorkspacePresets.LEGAL,
                    LcncWorkspacePresets.DOCUMENT_REVIEW, LcncWorkspacePresets.DOCUMENT_CORRECTION)) {
                assertEquals(if (name == LcncWorkspacePresets.DOCUMENT_REVIEW) 0 else 1,
                    p.nodes.view.count { it.type == "document.curate" })
                assertTrue(p.controls.humanOversight)
                assertFalse(p.nodes.view.any { it.type == "nal.rule.admit" || it.type == "http.post" })
                if (name in setOf(LcncWorkspacePresets.MEDICAL, LcncWorkspacePresets.LEGAL)) {
                    assertTrue(p.wires.view.any { it.fromPort == "ruleCandidates" && it.toNode == "candidates" })
                    assertFalse(p.nodes.view.any { it.type == DocumentCurationTasks.TASKS || it.type == DocumentCurationTasks.CORRECT })
                } else {
                    assertEquals(1, p.nodes.view.count { it.type == DocumentCurationTasks.TASKS })
                    assertFalse(p.nodes.view.any { it.type == "nars.reteFire" })
                    if (name == LcncWorkspacePresets.DOCUMENT_CORRECTION) {
                        assertTrue(p.wires.view.any { it.fromNode == "correction" && it.fromPort == "instructions" && it.toNode == "curate" })
                        assertTrue(p.wires.view.any { it.fromNode == "curate" && it.fromPort == "receiptCid" && it.toNode == "tasks" })
                    }
                }
            }
        }
    }
}
