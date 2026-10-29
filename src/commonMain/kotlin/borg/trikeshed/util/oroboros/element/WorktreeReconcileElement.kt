package borg.trikeshed.util.oroboros.element

import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import borg.trikeshed.context.Drain
import borg.trikeshed.lib.j
import borg.trikeshed.cursor.currentTimeMillis
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.util.oroboros.MemoryBridge
import borg.trikeshed.util.oroboros.WorktreeCouchGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * CCEK element for worktree reconciliation.
 * Watcher dirties and bounded sync requests share one serialized reconciliation worker.
 */
class WorktreeReconcileElement(
    private val repoRoot: String,
    private val worktreeCouchGateway: WorktreeCouchGateway,
    private val couchIndexBridge: CouchIndexBridge,
    private val memoryBridge: MemoryBridge,
    private val headShaProvider: suspend () -> String,
    parentJob: Job? = null
) : CoroutineContext.Element, Drain {
    companion object Key : CoroutineContext.Key<WorktreeReconcileElement>
    override val key: CoroutineContext.Key<*> = Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)
    /** Completes at the first [open]; [sync] refuses before it. */
    val opened: CompletableJob = Job()
    /** The reconcile loop, launched at [open]. */
    var worker: Job? = null

    val worktreeDirty = Channel<Unit>(Channel.CONFLATED)
    private val requests = Channel<CompletableDeferred<WorktreeCouchGateway.Snapshot>>(
        capacity = 64,
        onUndeliveredElement = { it.completeExceptionally(CancellationException("Worktree reconciliation stopped")) },
    )

    suspend fun open() {
        // The loop is a supervisor child before [opened] admits a sync, so drain joins it.
        val loop = CoroutineScope(currentCoroutineContext() + this + supervisor).launch(Dispatchers.Default, CoroutineStart.LAZY) {
            var dirtyOpen = true
            var requestsOpen = true
            while (dirtyOpen || requestsOpen) {
                select<Unit> {
                    if (requestsOpen) requests.onReceiveCatching { received ->
                        val result = received.getOrNull()
                        if (result == null) requestsOpen = false
                        else reconcile(result)
                    }
                    if (dirtyOpen) worktreeDirty.onReceiveCatching { received ->
                        if (received.isClosed) dirtyOpen = false
                        else {
                            delay(250)
                            worktreeDirty.tryReceive() // One pending conflated dirty; sync requests remain queued.
                            reconcile()
                        }
                    }
                }
            }
        }
        if (!opened.complete()) return loop.cancel()
        loop.invokeOnCompletion {
            requests.cancel()
            worktreeDirty.cancel()
            supervisor.complete()
        }
        worker = loop
        loop.start()
    }

    /** Returns after the accepted scan and its index/memory projections complete. */
    suspend fun sync(): WorktreeCouchGateway.Snapshot {
        check(opened.isCompleted) { "Worktree reconciliation is not open" }
        val result = CompletableDeferred<WorktreeCouchGateway.Snapshot>()
        requests.send(result)
        return result.await()
    }

    private suspend fun reconcile(result: CompletableDeferred<WorktreeCouchGateway.Snapshot>? = null) {
        try {
            val currentSha = headShaProvider()
            val snap = worktreeCouchGateway.reconcile(
                repoRoot = repoRoot,
                agentId = "oroboros",
                revision = currentSha,
                sequence = currentTimeMillis(),
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
            result?.complete(snap)
        } catch (failure: Throwable) {
            result?.completeExceptionally(failure)
            if (failure is CancellationException) throw failure
            println("[OROBOROS] Worktree→Couch reconcile failed: ${failure.message}")
            failure.printStackTrace()
        }
    }

    override suspend fun drain() = withContext(NonCancellable) {
        worktreeDirty.close()
        requests.close()
        supervisor.complete()
        supervisor.join()
    }

    suspend fun close() = drain()
}
