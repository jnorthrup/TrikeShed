<<<<<<< ours
// Rejected malformed Pijul materialization; intentionally inert pending a complete CCEK implementation.
=======
package borg.trikeshed.util.oroboros.element

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.j
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.util.oroboros.MemoryBridge
import borg.trikeshed.util.oroboros.WorktreeCouchGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * CCEK element for worktree reconciliation.
 * Bounded conflated event input triggers reconciliation against the worktree couch gateway.
 */
class WorktreeReconcileElement(
    private val repoRoot: String,
    private val worktreeCouchGateway: WorktreeCouchGateway,
    private val couchIndexBridge: CouchIndexBridge,
    private val memoryBridge: MemoryBridge,
    private val headShaProvider: suspend () -> String,
    parentJob: Job? = null
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<WorktreeReconcileElement>()
    override val key: CoroutineContext.Key<*> = Key

    val worktreeDirty = Channel<Unit>(Channel.CONFLATED)

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()

        kotlinx.coroutines.CoroutineScope(supervisor).launch(Dispatchers.IO) {
            while (isActive) {
                worktreeDirty.receive()
                delay(250)
                while (worktreeDirty.tryReceive().isSuccess) { /* coalesce */ }

                val currentSha = headShaProvider()
                runCatching {
                    val snap = worktreeCouchGateway.reconcile(
                        repoRoot = repoRoot,
                        agentId = "oroboros",
                        revision = currentSha,
                        sequence = System.currentTimeMillis(),
                    )
                    couchIndexBridge.indexReconciliation(
                        WorktreeCouchGateway.WORKTREE_PREFIX,
                        snap.paths.size j { i: Int -> snap.paths[i] },
                    )
                    val bridged = memoryBridge.bridge(snap, agentId = "oroboros")
                    println(
                        "[OROBOROS] Worktree→Couch reconcile: ${snap.paths.size} paths, " +
                            "$bridged memory updates @ ${currentSha.take(12)}"
                    )
                }.onFailure {
                    System.err.println("[OROBOROS] Worktree→Couch reconcile failed: ${it.message}")
                }
            }
        }
    }

    override suspend fun drain() {
        worktreeDirty.close()
        super.drain()
    }
}
>>>>>>> theirs
