package borg.trikeshed.lcnc

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Bounded application admission. Disconnecting a client does not discard an accepted write. */
class HeadhunterRequests(parent: CoroutineScope) {
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner)
    private val requests = Channel<Join<suspend () -> Any?, CompletableDeferred<Any?>>>(16,
        onUndeliveredElement = { it.b.completeExceptionally(CancellationException("Job agent request was not processed")) })

    init {
        repeat(3) {
            scope.launch {
                try {
                    for (request in requests) {
                        try { request.b.complete(request.a()) }
                        catch (failure: Throwable) { request.b.completeExceptionally(failure); currentCoroutineContext().ensureActive() }
                    }
                } finally {
                    if (!currentCoroutineContext().isActive) requests.cancel()
                }
            }
        }
    }

    suspend fun <T> call(block: suspend () -> T): T {
        val reply = CompletableDeferred<Any?>()
        requests.send(block j reply)
        @Suppress("UNCHECKED_CAST")
        return reply.await() as T
    }

    /** Closing admission drains the channel; all callers await the same owner completion. */
    suspend fun drain() = withContext(NonCancellable) {
        requests.close()
        owner.complete()
        owner.join()
    }
}
