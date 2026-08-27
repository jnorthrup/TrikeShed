package borg.trikeshed.kanban

import borg.trikeshed.ccek.ArticulatedNode
import borg.trikeshed.ccek.ForgeSignal
import borg.trikeshed.ccek.ProjectionKind
import borg.trikeshed.forge.ForgeDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * W1.5: retire duplicated scaffolding DELIBERATELY — a live [BoardStoreElement]
 * becomes observable/lowerable through ONE CCEK [ArticulatedNode], while the
 * element keeps its WAL/CAS/idempotency guarantees (those are real and CCEK
 * has none).
 *
 *  - Board commits re-enter the node as MoveCard signals: every agent
 *    subscribed on [node] participates in each commit's bounded fan-out.
 *  - CCEK-side callers lower to real store commands via [submit] — the raw
 *    map stays the durable truth (idempotency + CAS + WAL unchanged).
 *  - Replay remains the WAL's job; this seam adds no second store.
 */
class BoardCcekBridge(
    private val store: BoardStoreElement,
    parentScope: CoroutineScope,
) {
    val node: ArticulatedNode = ArticulatedNode(
        initialDoc = ForgeDoc.empty("board-${store.lastSequence}"),
        scope = parentScope,
        enabledProjections = setOf(ProjectionKind.BOARD),
    )

    private val collectJob = parentScope.launch {
        store.committed.collect { event ->
            // Re-emit each commit into the node so subscribed agents receive it
            // under the semaphore's bounds. The signal IS the notification.
            node.sendSignal(ForgeSignal.MoveCard(event.jobId, event.col.wire))
        }
    }

    /**
     * Submit one raw command through the store (durable path). Completes when
     * the single-writer reducer has applied or refused it.
     */
    suspend fun submit(raw: Map<*, *>): BoardApply {
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(raw, reply))
        return reply.await()
    }

    /** Cancel the observation seam only — never the store itself. */
    fun close() {
        collectJob.cancel()
        node.cancel()
    }
}
