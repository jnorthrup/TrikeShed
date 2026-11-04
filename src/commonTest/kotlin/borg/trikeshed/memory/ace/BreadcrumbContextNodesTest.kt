package borg.trikeshed.memory.ace

import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class BreadcrumbContextNodesTest {
    @Test fun offeredPresetProjectsAndExpandsLocalEvidenceWithoutAModel(): Unit = runBlocking(Airgap()) {
        val cas = CasStore.inMemory()
        val breadcrumbs = BreadcrumbElement(cas = cas)
        val program = LcncProgramConfix.fromJson("offline-context", LcncPresets.all().getValue("preset-breadcrumb-context"))
        val display = TribunalNodes.registry(TribunalDialog { _, _, _ -> error("Offline preset called a model") })
            .filterKeys { it == "display" }
        val registry = PureNodes.registry { 0L } + BrainMuxNodes.registry() + AceContextNodes.registry() + display +
            BreadcrumbContextNodes.registry(cas, breadcrumbs)
        val result = LcncRunner(registry).runAll(program)
        val projected = result.getValue("project")
        val recalled = result.getValue("recall")
        val source = program.nodes.view.first { it.id == "source" }.params.getValue("value")
        assertEquals(source, recalled["text"])
        assertEquals(ContentId.of(source.encodeToByteArray()).value, projected["sourceCid"])
        assertEquals(_l[projected["sourceCid"]], recalled["sourceCids"])
        val details = assertIs<Map<*, *>>(projected["projection"])
        assertEquals("heuristic", details["tier"])
        assertNull(details["model"])
        breadcrumbs.transact(projected["context"] as String, create = false) { context ->
            val sourceId = BreadcrumbId(projected["sourceId"] as String)
            val summaryId = BreadcrumbId(projected["breadcrumbId"] as String)
            assertEquals(sourceId, context.foreground?.a)
            assertEquals(_l[sourceId], context.lookup(summaryId)?.children)
            assertEquals(0.0, context.snapshot().view.first { it.id == summaryId }.presence.value)
            assertEquals(source, context.lookup(sourceId)?.content)
        }
    }

    @Test fun sourceRecallSelectsTheChildAndPreservesExactRawEvidence(): Unit = runBlocking(Airgap()) {
        val cas = CasStore.inMemory()
        val breadcrumbs = BreadcrumbElement(cas = cas)
        val runners = BreadcrumbContextNodes.registry(cas, breadcrumbs)
        val source = (1..12).joinToString("\n") { "Cobalt evidence item $it remains attributed to its source." }
        val project = runners.getValue(BreadcrumbContextNodes.PROJECT).run(LcncNode("project", BreadcrumbContextNodes.PROJECT,
            _m["maxChars" j "128"]), _m["source" j source, "question" j "Cobalt evidence", "context" j "local"])
        assertEquals(true, project["requiresExpansion"])
        breadcrumbs.transact("local", create = false) { context ->
            assertEquals(project["breadcrumbId"], context.foreground?.a?.value)
        }
        val recalled = runners.getValue(BreadcrumbContextNodes.RECALL).run(LcncNode("recall", BreadcrumbContextNodes.RECALL),
            _m["ref" j project["sourceRef"], "cid" j project["sourceCid"], "context" j "local"])
        assertEquals(source, recalled["text"])
        assertEquals(source, cas.get(ContentId(project["sourceCid"] as String))?.decodeToString())
        breadcrumbs.transact("local", create = false) { context ->
            assertEquals(project["sourceId"], context.foreground?.a?.value)
            assertEquals(0.0, context.snapshot().view.first { it.id.value == project["breadcrumbId"] }.presence.value)
            assertEquals(_l[BreadcrumbId(project["sourceId"] as String)], context.lookup(BreadcrumbId(project["breadcrumbId"] as String))?.children)
        }
    }

    @Test fun boundedRecallReportsOmissionAndKeepsDisjointRangesDistinct(): Unit = runBlocking(Airgap()) {
        val cas = CasStore.inMemory()
        val breadcrumbs = BreadcrumbElement(cas = cas)
        val runners = BreadcrumbContextNodes.registry(cas, breadcrumbs)
        val source = "Cobalt first evidence.\n" + "unrelated ".repeat(600) + "\nCobalt second evidence."
        val project = runners.getValue(BreadcrumbContextNodes.PROJECT).run(LcncNode("project", BreadcrumbContextNodes.PROJECT),
            _m["source" j source, "question" j "Cobalt", "context" j "bounded"])
        val recalled = runners.getValue(BreadcrumbContextNodes.RECALL).run(LcncNode("recall", BreadcrumbContextNodes.RECALL,
            _m["budgetChars" j "2048"]), _m["ref" j project["sourceRef"], "cid" j project["sourceCid"],
            "context" j "bounded", "question?" j "Cobalt"])
        val envelope = assertIs<Map<*, *>>(recalled["recall"])
        assertTrue((envelope["selectedChars"] as Number).toInt() > 0)
        assertTrue((envelope["omittedChars"] as Number).toInt() > 0)
        assertTrue((recalled["text"] as String).contains("\n…\n"))
        for (value in envelope["ranges"] as List<*>) {
            val range = value as Map<*, *>
            assertEquals(source.substring((range["start"] as Number).toInt(), (range["end"] as Number).toInt()), range["content"])
        }
    }

    @Test fun invalidBudgetsAndUnknownContextsRefuseWithoutCreatingMemory(): Unit = runBlocking(Airgap()) {
        val cas = CasStore.inMemory()
        val breadcrumbs = BreadcrumbElement(cas = cas)
        val runners = BreadcrumbContextNodes.registry(cas, breadcrumbs)
        assertFailsWith<IllegalArgumentException> {
            runners.getValue(BreadcrumbContextNodes.PROJECT).run(LcncNode("project", BreadcrumbContextNodes.PROJECT,
                _m["maxChars" j "127"]), _m["source" j "Cobalt evidence.", "question" j "Cobalt", "context" j "invalid"])
        }
        assertEquals(0, breadcrumbs.snapshot().size)
        assertFailsWith<BreadcrumbContextNotFoundException> {
            runners.getValue(BreadcrumbContextNodes.RECALL).run(LcncNode("recall", BreadcrumbContextNodes.RECALL),
                _m["ref" j "0000", "cid" j ContentId.of("missing".encodeToByteArray()).value, "context" j "missing"])
        }
        assertEquals(0, breadcrumbs.snapshot().size)
    }
}
