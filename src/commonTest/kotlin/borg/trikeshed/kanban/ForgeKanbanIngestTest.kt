package borg.trikeshed.kanban

import borg.trikeshed.dag.ReteWorkingMemory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForgeKanbanIngestTest {
    private val markdown = """
        TARGET: Unified Job Nexus

        5. Supervisor tree and dependency DAG

        6. Work packages

        G0 — Root graph
        Objective

        Make the graph real. Preserve "quoted text" and tabs:	value.

        F0 — Contract spine
        Depends on: G0.

        Prove one vertical cut.

        S1 — Durable state
        Depends on: F0.

        Persist committed state.

        7. Production-rule behavior tied to Kanban
    """.trimIndent()

    @Test
    fun sourceEnvelopeRoundTripsTheEntireMarkdownDescription() {
        val source = ForgeBoardPersistence.source("jim", markdown, "/tmp/hi")
        val encoded = ForgeBoardPersistence.encode(source)
        val decoded = ForgeBoardPersistence.decode(encoded)

        assertEquals(markdown, decoded.description)
        assertEquals(source.contentId, decoded.contentId)
        assertTrue(encoded.contains("\"description\":"))
        assertFalse(encoded.contains("\"cards\""))
    }

    @Test
    fun ingestReducesHermesStyleTasksLinksReteCorrelationsAndCausality() = runTest {
        val reduction = ForgeKanbanIngest.reduce(
            ForgeBoardPersistence.source("jim", markdown, "/tmp/hi")
        )

        assertEquals(listOf("G0", "F0", "S1"), reduction.board.cards.map { it.id.value })
        assertEquals("ready", reduction.board.cards.first { it.id.value == "G0" }.columnId.value)
        assertEquals("todo", reduction.board.cards.first { it.id.value == "F0" }.columnId.value)
        assertEquals(listOf("G0"), reduction.board.cards.first { it.id.value == "F0" }.dependencies.map { it.value })
        assertEquals(listOf("F0"), reduction.board.cards.first { it.id.value == "S1" }.dependencies.map { it.value })

        val f0 = reduction.correlations.first { it.taskId == "F0" }
        assertEquals(listOf("G0"), f0.parentIds)
        assertEquals(listOf("S1"), f0.childIds)
        assertFalse(f0.ready)

        assertEquals(3, reduction.reteFacts.count { it.fields["kind"] == "task" })
        assertEquals(2, reduction.reteFacts.count { it.fields["kind"] == "link" })
        assertEquals(listOf("G0"), reduction.causalNodes.first { it.nodeId == "F0" }.parentNodeIds)
        assertEquals(f0.causalKey, reduction.causalNodes.first { it.nodeId == "F0" }.causalKey)
    }

    @Test
    fun reduceAssertsEveryDerivedFactIntoTheCallerSuppliedWorkingMemory() = runTest {
        val source = ForgeBoardPersistence.source("jim", markdown, "/tmp/hi")
        val workingMemory = ReteWorkingMemory()

        val reduction = ForgeKanbanIngest.reduce(source, workingMemory)

        // reduce == the pure projection plus an assertion pass; the reduction is unchanged.
        assertEquals(ForgeKanbanIngest.project(source), reduction)

        val board = reduction.reteFacts.first().board
        assertEquals(3, workingMemory.query(board, "kind" to "task").size)
        assertEquals(2, workingMemory.query(board, "kind" to "link").size)
        reduction.reteFacts.forEach { fact ->
            assertEquals(listOf(fact), workingMemory.facts(fact.factId))
        }
    }

    @Test
    fun projectionPathPerformsNoWorkingMemoryAssertion() = runTest {
        val source = ForgeBoardPersistence.source("jim", markdown, "/tmp/hi")
        val workingMemory = ReteWorkingMemory()

        val projected = ForgeKanbanIngest.project(source)

        assertTrue(projected.reteFacts.isNotEmpty())
        projected.reteFacts.forEach { fact ->
            assertTrue(workingMemory.facts(fact.factId).isEmpty())
        }
    }

    @Test
    fun shapeGateAdmitsPlansAndRefusesProseBeforePersist() {
        assertEquals("P_S_6_WP_P_WD_P_WD_P_7", ForgeKanbanIngest.planShape(markdown))
        ForgeKanbanIngest.requirePlanShape(markdown, "/tmp/plan.md")

        val hi = "hi\n\nthis is the file that was at /tmp/hi that day. it is not a plan.\n"
        assertEquals("P_P_", ForgeKanbanIngest.planShape(hi))
        val e = assertFailsWith<IllegalArgumentException> { ForgeKanbanIngest.requirePlanShape(hi, "/tmp/hi") }
        assertTrue(e.message!!.contains("shape `P_P_`"))

        // packages outside any work-packages section: no `6` ⇒ refused
        val orphan = "1. Goal\nx\n\nG0 — Orphan\nbody\n\n7. Acceptance\n"
        assertFailsWith<IllegalArgumentException> { ForgeKanbanIngest.requirePlanShape(orphan, "orphan.md") }
    }
}
