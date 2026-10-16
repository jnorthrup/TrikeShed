package borg.trikeshed.util.oroboros.element

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.j
import borg.trikeshed.cursor.currentTimeMillis
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.util.oroboros.MemoryBridge
import borg.trikeshed.util.oroboros.WorktreeCouchGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<WorktreeReconcileElement>()
    override val key: CoroutineContext.Key<*> = Key

    val worktreeDirty = Channel<Unit>(Channel.CONFLATED)
    private val requests = Channel<CompletableDeferred<WorktreeCouchGateway.Snapshot>>(
        capacity = 64,
        onUndeliveredElement = { it.completeExceptionally(CancellationException("Worktree reconciliation stopped")) },
    )
    private val lifecycle = Mutex()

    override suspend fun open() {
        lifecycle.withLock {
            if (state != ElementState.CREATED) return
            super.open()
            CoroutineScope(currentCoroutineContext() + this + supervisor).launch(Dispatchers.Default) {
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
            }.invokeOnCompletion {
                requests.cancel()
                worktreeDirty.cancel()
                supervisor.complete()
            }
        }
    }

    /** Returns after the accepted scan and its index/memory projections complete. */
    suspend fun sync(): WorktreeCouchGateway.Snapshot {
        lifecycle.withLock {
            check(state == ElementState.OPEN || state == ElementState.ACTIVE) { "Worktree reconciliation is $state" }
        }
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
        lifecycle.withLock {
            if (state == ElementState.CLOSED) return@withContext
            state = ElementState.DRAINING
            worktreeDirty.close()
            requests.close()
            supervisor.complete()
        }
        supervisor.join()
        lifecycle.withLock { state = ElementState.CLOSED }
    }

    override suspend fun close() = drain()
}
