package borg.trikeshed.kanban

import borg.trikeshed.ccek.ForgeSignal
import borg.trikeshed.job.CasStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 8 gate, W1.5: the duplicated channel/SharedFlow scaffolding retires
 * into a CCEK view — commits fan out through the node to subscribed agents,
 * and signals lower to durable store commands. The WAL spine is untouched.
 */
class BoardCcekBridgeTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "board-bridge-$name-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun commitFansOutToSubscribedAgentAndSubmitLowersDurably() = runBlocking {
        val dir = tempDir("seam")
        val store = BoardStoreElement(JvmBoardWal(dir), CasStore.inMemory(), clock = { 42L })
        store.open()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = BoardCcekBridge(store, scope)

        val seen = CompletableDeferred<ForgeSignal.MoveCard>()
        bridge.node.subscribeAgent("observer") { signal ->
            if (signal is ForgeSignal.MoveCard && !seen.isCompleted) seen.complete(signal)
        }

        // Lower a durable submit THROUGH the bridge.
        val apply = bridge.submit(
            mapOf("type" to "submit", "jobId" to "a", "idempotencyKey" to "k1", "title" to "Card a"),
        )
        assertIs<BoardApply.Committed>(apply)

        // The commit fans back out as a CCEK signal to the subscribed agent.
        val signal = withTimeoutOrNull(2000) { seen.await() }
        assertTrue(signal != null, "commit reached the CCEK agent: $signal")
        assertEquals("a", signal!!.cardId)
        assertEquals(BoardCol.TODO.wire, signal.toColumnId)

        // The node stays live; the store keeps its sequence.
        assertTrue(bridge.node.isActive)
        assertEquals(1L, store.lastSequence)

        bridge.close()
        scope.cancel()
    }

    @Test
    fun refusalsNeverReachAgents() = runBlocking {
        val dir = tempDir("refused")
        val store = BoardStoreElement(JvmBoardWal(dir), CasStore.inMemory(), clock = { 42L })
        store.open()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bridge = BoardCcekBridge(store, scope)

        var badSignals = 0
        bridge.node.subscribeAgent("watcher") { _ -> badSignals++ }

        // Duplicate idempotency key: first commits, second refuses.
        val first = bridge.submit(mapOf("type" to "submit", "jobId" to "a", "idempotencyKey" to "k1"))
        assertIs<BoardApply.Committed>(first)
        delay(100) // let the commit signal land
        val agentSignalAtCommit = badSignals

        val dup = bridge.submit(mapOf("type" to "submit", "jobId" to "a", "idempotencyKey" to "k1"))
        assertIs<BoardApply.Rejected>(dup)
        delay(100)

        assertEquals(agentSignalAtCommit, badSignals,
            "a refusal must not masquerade as a commit event for agents")

        bridge.close()
        scope.cancel()
    }
}
