package borg.trikeshed.ccek

import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.toForgeDocument
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 1 gate test: CCEK incarnate.
 *
 * Uses runBlocking (not runTest) because ArticulatedNode fans out on
 * Dispatchers.Default — real threads, not the test dispatcher.
 */
class CcekIncarnationTest {

    private fun seedDoc() = KanbanBoard(
        id = KanbanBoardId("test"),
        name = "Test",
        columns = listOf(KanbanColumn(KanbanColumnId("backlog"), "Backlog", 0)),
        cards = listOf(
            KanbanCard(
                id = KanbanCardId("c1"),
                title = "Card 1",
                description = "",
                columnId = KanbanColumnId("backlog"),
                order = 0,
                priority = CardPriority.MEDIUM,
            ),
        ),
    ).toForgeDocument()

    private fun newNode(
        doc: borg.trikeshed.forge.ForgeDocument = seedDoc(),
        maxConcurrency: Int = 4,
    ): Pair<ArticulatedNode, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val node = ArticulatedNode(
            initialDoc = doc,
            scope = scope,
            record = true,
            maxConcurrency = maxConcurrency,
        )
        return node to scope
    }

    // ── W1.1: suspend agents ──────────────────────────────────────────────

    @Test
    fun suspendAgentReceivesSignal() = runBlocking {
        val (node, scope) = newNode()
        val received = CompletableDeferred<ForgeSignal>()

        node.subscribeAgent("receiver") { signal ->
            received.complete(signal)
        }

        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "hello"))

        val got = withTimeoutOrNull(2000) { received.await() }
        assertTrue(got is ForgeSignal.AppendBlock, "agent received signal")
        assertEquals("hello", (got as ForgeSignal.AppendBlock).text)

        scope.cancel()
    }

    @Test
    fun suspendAgentCanPerformSuspendWork() = runBlocking {
        val (node, scope) = newNode()
        val completed = CompletableDeferred<String>()

        node.subscribeAgent("async-worker") { signal ->
            delay(10) // real delay — proves suspend works
            completed.complete("processed:${(signal as ForgeSignal.AppendBlock).text}")
        }

        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "data"))
        val result = withTimeoutOrNull(2000) { completed.await() }
        assertEquals("processed:data", result)

        scope.cancel()
    }

    // ── W1.2: bounded fan-out ─────────────────────────────────────────────

    @Test
    fun boundedConcurrencyRespectsSemaphore() = runBlocking {
        val (node, scope) = newNode(maxConcurrency = 2)
        val counters = Mutex()
        var running = 0
        var peak = 0
        val allDone = CompletableDeferred<Unit>()

        val agentCount = 6
        var completedCount = 0

        repeat(agentCount) { i ->
            node.subscribeAgent("slow-$i") { _ ->
                counters.withLock {
                    running++
                    peak = maxOf(peak, running)
                }
                delay(20)
                counters.withLock {
                    running--
                    completedCount++
                    if (completedCount >= agentCount) allDone.complete(Unit)
                }
            }
        }

        try {
            node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "go"))
            withTimeout(5000) { allDone.await() }
            counters.withLock {
                assertEquals(agentCount, completedCount)
                assertEquals(0, running)
                assertTrue(peak in 1..2, "peak concurrency $peak must be between 1 and 2")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun agentStatusEventsAreEmitted() = runBlocking {
        val (node, scope) = newNode()
        val statusEvents = mutableListOf<AgentStatusEvent>()

        val collectorJob = launch {
            node.agentStatus.collect { event ->
                statusEvents.add(event)
            }
        }

        node.subscribeAgent("tracked") { _ ->
            delay(20)
        }

        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "track-me"))
        delay(500) // let events flow through semaphore + agent + collector

        assertTrue(statusEvents.any { it is AgentStatusEvent.Started },
            "Started event emitted: $statusEvents")
        assertTrue(statusEvents.any { it is AgentStatusEvent.Completed },
            "Completed event emitted: $statusEvents")

        collectorJob.cancel()
        scope.cancel()
    }

    // ── projection fan-out ────────────────────────────────────────────────

    @Test
    fun projectionsEmitAfterSignal() = runBlocking {
        val (node, scope) = newNode()
        val boards = mutableListOf<KanbanBoard>()

        val collectorJob = launch {
            node.boardProjections.collect { board ->
                boards.add(board)
            }
        }

        delay(50) // let collector attach
        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "new block"))
        delay(500) // let signal process + fanOutAll on real threads

        assertTrue(boards.isNotEmpty(),
            "at least 1 board projection after signal, got ${boards.size}")

        collectorJob.cancel()
        scope.cancel()
    }

    // ── recording ─────────────────────────────────────────────────────────

    @Test
    fun recordingCapturesAllSignals() = runBlocking {
        val (node, scope) = newNode()

        node.subscribeAgent("noop") { _ -> }

        val signals = listOf(
            ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "a"),
            ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "b"),
            ForgeSignal.UpdateText("x", "y"),
        )
        signals.forEach { node.sendSignal(it) }
        delay(500)

        val recorded = node.recording()
        assertTrue(recorded.size >= 3,
            "recording captured at least 3 signals, got ${recorded.size}")

        scope.cancel()
    }

    // ── graceful shutdown ─────────────────────────────────────────────────

    @Test
    fun cancelDrainsWithoutException() = runBlocking {
        val (node, scope) = newNode()

        node.subscribeAgent("drain-test") { _ ->
            delay(10)
        }

        repeat(5) { i ->
            node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "msg-$i"))
        }
        node.cancel()
        delay(500)

        // Key invariant: no exception thrown during cancel+drain.
        scope.cancel()
    }

    // ── multiple concurrent agents ────────────────────────────────────────

    @Test
    fun multipleAgentsAllReceiveSameSignal() = runBlocking {
        val (node, scope) = newNode()
        val received = mutableMapOf<String, ForgeSignal>()
        val receivedMutex = Mutex()
        val allReceived = CompletableDeferred<Unit>()

        repeat(3) { i ->
            node.subscribeAgent("agent-$i") { signal ->
                receivedMutex.withLock {
                    received["agent-$i"] = signal
                    if (received.size >= 3) allReceived.complete(Unit)
                }
            }
        }

        try {
            node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "broadcast"))
            withTimeout(2000) { allReceived.await() }
            receivedMutex.withLock {
                assertEquals(3, received.size, "all 3 agents received the signal")
                assertTrue(received.values.all { it is ForgeSignal.AppendBlock })
            }
        } finally {
            scope.cancel()
        }
    }
}
