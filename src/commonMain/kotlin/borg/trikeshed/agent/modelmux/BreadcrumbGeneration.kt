package borg.trikeshed.agent.modelmux

import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One immutable session and the admitted host crossings that belong to its guest generation. */
class BreadcrumbGeneration(scope: CoroutineScope, val session: String, capacity: Int = 16) {
    private class Work(val run: suspend () -> Unit, val reject: (Throwable) -> Unit)

    init {
        require(session.isNotBlank() && session.length <= 128)
        require(capacity > 0)
    }

    private val supervisor = SupervisorJob(requireNotNull(scope.coroutineContext[Job]) { "generation requires an owning Job" })
    private val input = Channel.buffered<Work>(capacity) { it.reject(CancellationException("breadcrumb generation stopped")) }
    private val worker = CoroutineScope(scope.coroutineContext + supervisor).launch {
        try {
            for (work in input.kChannel) work.run()
        } finally {
            input.kChannel.cancel(CancellationException("breadcrumb generation stopped"))
        }
    }

    /** A successful send admits the crossing even if its caller subsequently stops waiting. */
    suspend fun <T> execute(operation: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        val work = Work({
            try { result.complete(operation()) }
            catch (failure: Throwable) {
                result.completeExceptionally(failure)
                if (failure is CancellationException) throw failure
            }
        }, { result.completeExceptionally(it) })
        input.kChannel.send(work)
        return result.await()
    }

    /** Close intake and wait for every admitted crossing; cancellation is not a substitute for drain. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        input.close()
        worker.join()
        supervisor.complete()
        supervisor.join()
    }
}
