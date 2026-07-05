package borg.trikeshed.ccek

import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CCEK Table-Testing Vision — TDD RED.
 *
 * These tests describe the fully-realized CCEK where:
 *   - User contexts are coroutine-scoped layers over causal/rete assertions
 *   - Models are table-tested against classfile polyglot blackboard facts
 *   - Blockly / graphical flows relay causality as cursor-backed spreadsheets
 *   - LCNC meta-paradigms adapt to the same articulation surface
 *
 * They FAIL today because the types they reference do not exist yet.
 *
 * RED areas:
 *   1. UserContext — coroutine-scoped epistemic layer
 *   2. CausalReteTable — table-test assertions over rete facts
 *   3. PolyglotBlackboard — classfile pointcut facts accessible to models
 *   4. GraphicalFlow — Blockly-style block graph that projects causality
 *   5. SpreadsheetVeneer — cursor-backed faceted grid over assertions
 *   6. MetaLcncAdapter — LCNC paradigm adaptation surface
 */
class CceTableTestingVisionTest {

    // ─── 1. UserContext: coroutine-scoped epistemic layer ─────────────────────

    @Test fun `user context layers assertions over causal rete`() = runBlocking {
        val binding = CCEK.initialize(MuxReactorElement())
        val ctx = binding.createUserContext("tester-1")
        ctx.activate()

        // Assert a causal fact into the rete network
        ctx.assertFact(CausalAssertion("build:success", mapOf("module" to "forge", "time" to "42ms")))

        // The assertion must be visible in the ctx's rete table
        val table = ctx.reteTable
        assertTrue(table.rowCount > 0, "rete table must contain asserted facts")
        assertTrue(table.containsFact("build:success"), "rete table must contain build:success")

        ctx.deactivate()
    }

    // ─── 2. CausalReteTable: table-test assertions ────────────────────────────

    @Test fun `rete table tests model predictions against facts`() = runBlocking {
        val binding = CCEK.initialize(MuxReactorElement())
        val ctx = binding.createUserContext("model-tester")
        ctx.activate()

        // Seed facts from a polyglot classfile scan
        ctx.assertFact(CausalAssertion("class:loaded", mapOf("name" to "ForgeDoc", "methods" to "7")))
        ctx.assertFact(CausalAssertion("method:invoked", mapOf("name" to "appendBlock", "count" to "3")))

        // Run a model prediction: "if appendBlock was called 3 times, doc should have 4 blocks"
        val prediction = ctx.predictModel("block-count-model", mapOf(
            "method" to "appendBlock",
            "count" to "3",
        ))

        assertNotNull(prediction)
        assertEquals(4, prediction["expectedBlocks"], "model predicts 4 blocks (1 initial + 3 appended)")

        // Table-test: verify prediction against actual rete facts
        val result = ctx.tableTest(prediction)
        assertTrue(result.passed, "table test must pass when facts match prediction")
        assertNotNull(result.evidence)

        ctx.deactivate()
    }

    // ─── 3. PolyglotBlackboard: classfile pointcut facts ──────────────────────

    @Test fun `polyglot blackboard exposes classfile pointcut facts to models`() = runBlocking {
        val binding = CCEK.initialize(MuxReactorElement())
        val ctx = binding.createUserContext("pointcut-tester")
        ctx.activate()

        // Load classfile pointcut facts into the blackboard
        ctx.loadPolyglotFacts(listOf(
            PolyglotFact("jvm", "GETFIELD", "ForgeDoc.blocks", "read"),
            PolyglotFact("jvm", "INVOKEVIRTUAL", "ForgeDoc.appendBlock", "call"),
            PolyglotFact("js", "PROPERTY_GET", "doc.blocks", "read"),
        ))

        // Models can query the polyglot blackboard
        val jvmReads = ctx.queryPolyglot(language = "jvm", kind = "read")
        assertTrue(jvmReads.isNotEmpty(), "must find jvm read pointcuts")
        assertTrue(jvmReads.any { it.target.contains("blocks") }, "must find blocks read")

        val jsReads = ctx.queryPolyglot(language = "js", kind = "read")
        assertTrue(jsReads.isNotEmpty(), "must find js read pointcuts")

        ctx.deactivate()
    }

    // ─── 4. GraphicalFlow: Blockly-style block graph ──────────────────────────

    @Test fun `graphical flow projects causality as cursor-backed blocks`() = runBlocking {
        val binding = CCEK.initialize(MuxReactorElement())
        val ctx = binding.createUserContext("flow-builder")
        ctx.activate()

        // Build a Blockly-style flow: signal → filter → project
        val flow = ctx.createGraphicalFlow("kanban-pipeline")
        flow.addBlock(GraphicalBlock("input", "Signal Input", mapOf("type" to "ForgeSignal")))
        flow.addBlock(GraphicalBlock("filter", "Filter by Priority", mapOf("priority" to "HIGH")))
        flow.addBlock(GraphicalBlock("project", "Project to Board", mapOf("view" to "kanban")))
        flow.connect("input", "filter")
        flow.connect("filter", "project")

        // The flow must be cursor-backed: queryable as a spreadsheet
        val cursor = flow.asCursor()
        assertEquals(3, cursor.size, "flow cursor must have 3 block rows")

        // The flow must relay causality: edges are visible
        val edges = flow.edges()
        assertEquals(2, edges.size, "must have 2 edges: input→filter, filter→project")
        assertTrue(edges.any { it.from == "input" && it.to == "filter" })

        ctx.deactivate()
    }

    // ─── 5. SpreadsheetVeneer: cursor-backed faceted grid ─────────────────────

    @Test fun `spreadsheet veneer facets assertions into columns`() = runBlocking {
        val binding = CCEK.initialize(MuxReactorElement())
        val ctx = binding.createUserContext("spreadsheet-tester")
        ctx.activate()

        ctx.assertFact(CausalAssertion("card:moved", mapOf(
            "cardId" to "c1",
            "from" to "backlog",
            "to" to "done",
            "agent" to "rete-agent",
        )))
        ctx.assertFact(CausalAssertion("card:moved", mapOf(
            "cardId" to "c2",
            "from" to "backlog",
            "to" to "inprogress",
            "agent" to "rete-agent",
        )))

        // Project assertions into a spreadsheet veneer
        val sheet = ctx.spreadsheetVeneer()
        assertTrue(sheet.rowCount >= 2, "spreadsheet must have at least 2 rows")

        // Facet by 'to' column
        val doneRows = sheet.facet(column = "to", value = "done")
        assertEquals(1, doneRows.size, "one row must have to=done")

        val inProgressRows = sheet.facet(column = "to", value = "inprogress")
        assertEquals(1, inProgressRows.size, "one row must have to=inprogress")

        ctx.deactivate()
    }

    // ─── 6. MetaLcncAdapter: LCNC paradigm adaptation ─────────────────────────

    @Test fun `meta lcnc adapter adapts paradigm to articulation surface`() = runBlocking {
        val binding = CCEK.initialize(MuxReactorElement())
        val ctx = binding.createUserContext("lcnc-adapter-tester")
        ctx.activate()

        // Define an LCNC paradigm: "kanban-mover" — moves cards based on rete rules
        val paradigm = MetaLcncParadigm(
            name = "kanban-mover",
            rules = listOf(
                LcncRule("auto-advance", "when status=done → mark card complete"),
                LcncRule("wip-limit", "when column=inprogress count>3 → block new cards"),
            ),
        )

        // Adapt the paradigm to the articulation surface
        val adapted = ctx.adaptParadigm(paradigm)
        assertTrue(adapted.isActive, "adapted paradigm must be live")

        // The adapted paradigm must be queryable as a rete table
        val table = adapted.reteTable
        assertTrue(table.rowCount >= 2, "rete table must contain paradigm rules as facts")

        ctx.deactivate()
    }

    // ─── 7. Integrated: signal → causal assertion → model test → projection ───

    @Test fun `end-to-end signal assertion model test and projection`() = runBlocking {
        val binding = CCEK.initialize(MuxReactorElement())
        val ctx = binding.createUserContext("integration-tester")
        ctx.activate()

        // 1. Create a forge doc node under this user context
        val doc = ForgeDoc.empty("Integration Doc")
        val node = ctx.choreograph(doc)

        delay(100)

        // 2. Send a signal
        node.sendSignal(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Integration Task",
            mapOf("kanban.status" to "backlog"),
        ))

        delay(300)

        // 3. The signal must have produced a causal assertion
        val facts = ctx.reteTable
        assertTrue(facts.containsFact("block:appended"), "appendBlock must produce causal assertion")

        // 4. The board projection must reflect the new card
        val board = withTimeout(3000) {
            node.boardProjections.first { b ->
                b.cards.any { it.title == "Integration Task" }
            }
        }
        assertEquals(1, board.cards.size)

        ctx.deactivate()
    }

    // ─── 8. Context isolation: separate user contexts don't leak ───────────────

    @Test fun `separate user contexts are isolated`() = runBlocking {
        val bindingA = CCEK.initialize(MuxReactorElement())
        val bindingB = CCEK.initialize(MuxReactorElement())
        val ctxA = bindingA.createUserContext("user-a")
        val ctxB = bindingB.createUserContext("user-b")
        ctxA.activate()
        ctxB.activate()

        ctxA.assertFact(CausalAssertion("test:fact", mapOf("ctx" to "a")))
        ctxB.assertFact(CausalAssertion("test:fact", mapOf("ctx" to "b")))

        // Each context sees only its own facts
        val tableA = ctxA.reteTable
        val tableB = ctxB.reteTable

        val aFacts = tableA.query("test:fact")
        val bFacts = tableB.query("test:fact")

        assertEquals(1, aFacts.size)
        assertEquals("a", aFacts.first().fields["ctx"])
        assertEquals(1, bFacts.size)
        assertEquals("b", bFacts.first().fields["ctx"])

        ctxA.deactivate()
        ctxB.deactivate()
    }
}