package borg.trikeshed.userspace.concurrency

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement

/**
 * Structured concurrency patterns ported from literbike.
 * Leveraging Kotlin's native coroutine infrastructure.
 */

class CancellationError(message: CharSequence) : CancellationException(message.toString())

/**
 * Job abstraction ported from literbike.
 * In Kotlin, this is backed by [kotlinx.coroutines.Job].
 */
interface Job : borg.trikeshed.ccek.KeyedService {
    fun isActive(): Boolean
    fun cancel(cause: CancellationException? = null)

    companion object Key : CoroutineContext.Key<Job>
}

/**
 * SuspendToken ported from literbike.
 * Similar to [CompletableDeferred] in Kotlin.
 */
class SuspendToken<T>(initial: T? = null) {
   val deferred = CompletableDeferred<T>()

    init {
        if (initial != null) {
            // In Rust it starts as Running, initial might just be a hint or unused in some states
        }
    }

    fun isSuspended(): Boolean = !deferred.isCompleted

    fun resume(value: T) {
        deferred.complete(value)
    }

    fun complete(value: T) {
        deferred.complete(value)
    }

    fun cancel() {
        deferred.cancel()
    }

    suspend fun await(): T = deferred.await()
}
