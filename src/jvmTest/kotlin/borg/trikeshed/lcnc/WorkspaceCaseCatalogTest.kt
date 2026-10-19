package borg.trikeshed.lcnc

import borg.trikeshed.kanban.module.WorkspaceService
import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkspaceCaseCatalogTest {
    @Test fun caseOfferingsProjectValidatedCurrentProgramsWithoutExecutionOrPublication() = runTest {
        val context = vmRuntimeTestContext(this)
        val name = "preset-tribunal-case-v2"
        val offered = WorkspaceService(context).catalog()
        assertEquals(setOf("preset-tribunal-case", "preset-legal-tribunal-case", name, "preset-legal-tribunal-case-v2"),
            assertIs<List<*>>(offered["cases"]).map { (it as Map<*, *>)["name"] }.toSet())
        assertTrue(context.blackboard.snapshot().values.isEmpty(), "Discovery must not install offered presets")
        val program = LcncProgramConfix.fromJson(name, LcncPresets.all().getValue(name))
        val entry = LcncBlackboard.programEntry(name, program, LcncContracts.all().associateBy { it.type })
        context.blackboard.put(LcncBlackboard.programKey(name), entry, "test")
        context.blackboard.put(LcncBlackboard.programKey("foreign"), entry, "test")
        val invalid = program.copy(name = "invalid", nodes = s_[LcncNode("x", "mux.chat")])
        context.blackboard.put(LcncBlackboard.programKey("invalid"),
            LcncBlackboard.programEntry("invalid", invalid, LcncContracts.all().associateBy { it.type }), "test")
        val revision = context.blackboard.snapshot().revision
        val catalog = WorkspaceService(context).catalog()
        val cases = assertIs<List<*>>(catalog["cases"]).map { assertIs<Map<*, *>>(it) }
        assertEquals(4, cases.size)
        assertFalse(cases.any { it["name"] in setOf("foreign", "invalid") })
        val selected = cases.single { it["name"] == name }
        assertEquals(LcncBlackboard.cidOf(program), selected["programCid"])
        val edges = assertIs<List<*>>(selected["edges"])
        assertEquals(program.kanban!!.edges.size, edges.size)
        assertTrue(edges.any { (it as Map<*, *>)["to"] == "rule" })
        assertEquals(revision, context.blackboard.snapshot().revision)
        assertTrue(assertIs<List<*>>(catalog["facets"]).any { (it as Map<*, *>)["id"] == "cases" })
        context.blackboard.put(LcncBlackboard.programKey(name), mapOf("name" to name, "document" to emptyMap<String, Any>()), "test")
        assertFalse(WorkspaceService(context).casePrograms().any { it["name"] == name },
            "An invalid current definition must not silently fall back to the offered preset")
    }
}
