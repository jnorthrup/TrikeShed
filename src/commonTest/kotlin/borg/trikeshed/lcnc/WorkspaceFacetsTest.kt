package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlin.test.*

class WorkspaceFacetsTest {
    val source = """{"nodes":[{"id":"雪","params":{"text":"a\\b","value":null}}],"wires":[]}"""

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

    @Test fun domainPresetsReuseCuratorWithoutAutomaticRuleAdmission() {
        for ((name, json) in LcncWorkspacePresets.documents()) {
            val p = LcncProgramConfix.fromJson(name, json)
            assertTrue(LcncTypeCheck.check(p).isEmpty(), "$name: ${LcncTypeCheck.check(p)}")
            if (name != LcncWorkspacePresets.POLYGLOT) {
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
