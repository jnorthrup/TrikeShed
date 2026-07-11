package borg.trikeshed.userspace.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.Liburing
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.context.AsyncContextKey
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * Fanout dispatcher CCEK element for liburing completion dispatch.
 * Moved here from userspace/LiburingElement.kt for proper package organization.
 */
open class FanoutDispatcherElement(
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.FanoutDispatcherKey

    private val handlers = mutableMapOf<Long, MutableList<(UringCompletion) -> Unit>>()

    /** Register a handler for completions with the given userData token. */
    fun registerHandler(userData: Long, handler: (UringCompletion) -> Unit) {
        handlers.getOrPut(userData) { mutableListOf() }.add(handler)
        // Also register with liburing facade
        Liburing.registerFanoutHandler(userData, handler)
    }

    /** Remove a handler. */
    fun removeHandler(userData: Long, handler: (UringCompletion) -> Unit) {
        handlers[userData]?.remove(handler)
        if (handlers[userData].isNullOrEmpty()) handlers.remove(userData)
        Liburing.removeFanoutHandler(userData, handler)
    }

    /** Dispatch a completion to all handlers for its userData. */
    internal fun dispatch(completion: UringCompletion) {
        handlers[completion.userData]?.toList()?.forEach { it(completion) }
    }

    override suspend fun close() {
        handlers.clear()
        super.close()
    }
}