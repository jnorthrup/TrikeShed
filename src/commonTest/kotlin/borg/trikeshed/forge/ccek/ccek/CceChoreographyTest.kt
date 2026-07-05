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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CCEK Vision — TDD RED.
 *
 * These tests describe the fully-realized Channelized Coroutine
 * Execution Kernel: Autotools × Notion → Forge.
 *
 * They FAIL today because the types they reference do not exist yet.
 * Each passing test closes one gap in the staged roadmap.
 *
 * RED areas:
 *   1. CCEK.choreograph() — the configure+make entry point
 *   2. ArticulatedNode — a live node with scopes + channels
 *   3. Multi-projection fan-out (Kanban + Markdown + Agent)
 *   4. Hierarchical composition (parent → child scopes)
 *   5. Structured cancellation (cancel parent → children stop)
 *   6. Agent integration (signal → agent fires → projection updates)
 *   7. Self-reconfiguration (facet change → re-induce → re-fan-out)
 *   8. Recording / replay (channel events are deterministic frames)
 */
class CceChoreographyTest {

    // ─── 1. CCEK.choreograph() entry point ────────────────────────────────────

    @Test fun `choreograph creates articulated node from document`() = runTest {
        val doc = ForgeDoc.empty("Project Alpha")
        val binding = CCEK.initialize(MuxReactorElement())
        val node = binding.choreograph(doc)

        assertNotNull(node, "choreograph must return a non-null ArticulatedNode")
        assertTrue(node.isActive, "articulated node must be live after choreograph")
        node.cancel()
    }

    // ─── 2. ArticulatedNode has document, board, and markdown projections ─────

    @Test fun `articulated node fans out to document board and markdown`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Vision Doc")
        val node = binding.choreograph(doc)

        // All three projection channels must exist and emit initial state
        val docProj = withTimeout(2000) { node.documentProjections.first() }
        val boardProj = withTimeout(2000) { node.boardProjections.first() }
        val mdProj = withTimeout(2000) { node.markdownProjections.first() }

        assertEquals("Vision Doc", docProj.rootPageId.let { docProj.requireBlock(it).text })
        assertEquals("Vision Doc", boardProj.name)
        assertTrue(mdProj.contains("Vision Doc"))

        node.cancel()
    }

    // ─── 3. Signal input → all projections update atomically ──────────────────

    @Test fun `signal fans out to all projections simultaneously`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Fan-Out Test")
        val node = binding.choreograph(doc)
        delay(100) // let initial state emit

        node.sendSignal(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Task Alpha",
            mapOf("kanban.status" to "backlog", "kanban.priority" to "high"),
        ))

        // Board must show the new card
        val boardProj = withTimeout(3000) {
            node.boardProjections.first { board ->
                board.cards.any { it.title == "Task Alpha" }
            }
        }
        assertEquals(1, boardProj.cards.size)
        assertEquals(CardPriority.HIGH, boardProj.cards.first().priority)

        // Markdown must contain the heading
        val mdProj = withTimeout(3000) {
            node.markdownProjections.first { md -> md.contains("Task Alpha") }
        }
        assertTrue(mdProj.contains("Task Alpha"))

        node.cancel()
    }

    // ─── 4. Hierarchical composition: parent scope → child scopes ─────────────

    @Test fun `articulated node spawns child scopes for sub-blocks`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Hierarchy")
        val node = binding.choreograph(doc)
        delay(100)

        node.sendSignal(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Parent Task",
            mapOf("kanban.status" to "backlog"),
        ))

        delay(200) // let fan-out settle

        // The node should have created child scopes for the new block
        assertTrue(node.childScopeCount > 0, "adding a block must spawn child scopes")

        node.cancel()
    }

    // ─── 5. Structured cancellation: cancel parent → children stop ────────────

    @Test fun `cancelling articulated node stops all child scopes`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Cancellation Test")
        val node = binding.choreograph(doc)
        delay(100)

        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "block 1"))
        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "block 2"))
        delay(200)

        val childrenBeforeCancel = node.childScopeCount
        assertTrue(childrenBeforeCancel > 0, "must have children before cancel")

        node.cancel()
        delay(200)

        assertTrue(!node.isActive, "node must be inactive after cancel")
        assertEquals(0, node.childScopeCount, "all child scopes must be cancelled")
    }

    // ─── 6. Agent integration: signal → agent fires → projection updates ──────

    @Test fun `agent subscription receives signals and can write back`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Agent Test")
        val node = binding.choreograph(doc)
        delay(100)

        // Register an agent that listens for signals and auto-assigns cards
        val agentFires = mutableListOf<String>()
        node.subscribeAgent("auto-assigner") { signal ->
            if (signal is ForgeSignal.AppendBlock && signal.kind == ForgeBlockKind.HEADING_2) {
                agentFires.add(signal.text)
            }
        }

        node.sendSignal(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Auto-Assigned Task",
            mapOf("kanban.status" to "backlog"),
        ))

        delay(500) // give agent time to fire

        assertTrue(
            agentFires.contains("Auto-Assigned Task"),
            "agent must fire on matching signal, saw: $agentFires",
        )

        node.cancel()
    }

    // ─── 7. Self-reconfiguration: facet change triggers re-fan-out ────────────

    @Test fun `property change triggers re-projection`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Reconfigure")
        val node = binding.choreograph(doc)
        delay(100)

        // Add a card in backlog
        node.sendSignal(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Reconfigurable Card",
            mapOf("kanban.status" to "backlog"),
        ))

        delay(200)

        // Wait for it to appear in backlog
        val beforeBoard = withTimeout(3000) {
            node.boardProjections.first { board ->
                board.cards.any { it.title == "Reconfigurable Card" }
            }
        }
        assertEquals(KanbanColumnId("col-backlog"), beforeBoard.cards.first().columnId)

        // Change its status to done via MoveCard signal
        val cardId = beforeBoard.cards.first().id.value
        node.sendSignal(ForgeSignal.MoveCard(cardId, "col-done"))

        // Board must re-project with the card in done
        val afterBoard = withTimeout(3000) {
            node.boardProjections.first { board ->
                board.cards.any { it.columnId == KanbanColumnId("col-done") && it.title == "Reconfigurable Card" }
            }
        }
        assertEquals(KanbanColumnId("col-done"), afterBoard.cards.first().columnId)

        node.cancel()
    }

    // ─── 8. Recording: channel events are deterministic frames ─────────────────

    @Test fun `recording captures signal sequence for replay`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Recording")
        val node = binding.choreograph(doc, record = true)
        delay(100)

        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "frame 1"))
        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "frame 2"))
        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "frame 3"))

        delay(300)
        node.cancel()

        val recording = node.recording()
        assertEquals(3, recording.size, "recording must capture all 3 signals")
        assertTrue(recording.all { it is ForgeSignal.AppendBlock }, "all frames must be AppendBlock")
    }

    // ─── 9. Multi-view: Kanban board is one of many projections ───────────────

    @Test fun `kanban board and markdown stay in sync`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Sync Test")
        val node = binding.choreograph(doc)
        delay(100)

        node.sendSignal(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Synced Item",
            mapOf("kanban.status" to "backlog", "kanban.priority" to "critical"),
        ))

        // Both projections must contain the same data
        val boardProj = withTimeout(3000) {
            node.boardProjections.first { board ->
                board.cards.any { it.title == "Synced Item" }
            }
        }
        val mdProj = withTimeout(3000) {
            node.markdownProjections.first { md -> md.contains("Synced Item") }
        }

        assertEquals("Synced Item", boardProj.cards.first().title)
        assertEquals(CardPriority.CRITICAL, boardProj.cards.first().priority)
        assertTrue(mdProj.contains("Synced Item"))

        node.cancel()
    }

    // ─── 10. Autotools configure: facet-driven projection activation ──────────

    @Test fun `configure activates only matching projection contracts`() = runTest {
        val binding = CCEK.initialize(MuxReactorElement())
        val doc = ForgeDoc.empty("Configure Test")
        val node = binding.choreograph(
            doc,
            enabledProjections = setOf(ProjectionKind.BOARD),
        )
        delay(100)

        node.sendSignal(ForgeSignal.AppendBlock(
            ForgeBlockKind.HEADING_2, "Configured Item",
            mapOf("kanban.status" to "backlog"),
        ))

        // Board must update
        val boardProj = withTimeout(3000) {
            node.boardProjections.first { board ->
                board.cards.any { it.title == "Configured Item" }
            }
        }
        assertNotNull(boardProj)

        // Markdown should NOT emit because it's not in enabledProjections
        delay(500)
        assertTrue(
            node.markdownProjectionCount == 0,
            "markdown must not emit when not enabled, saw ${node.markdownProjectionCount}",
        )

        node.cancel()
    }
}