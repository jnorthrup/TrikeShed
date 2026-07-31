package borg.trikeshed.userspace.reactor

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.Liburing
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.context.AsyncContextKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
    
    private val dispatchChannel = Channel<UringCompletion>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    override suspend fun open() {
        super.open()
        CoroutineScope(supervisor).launch {
            for (completion in dispatchChannel) {
                handlers[completion.userData]?.toList()?.forEach { it(completion) }
            }
        }
    }

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
    internal suspend fun dispatch(completion: UringCompletion) {
        val result = dispatchChannel.trySend(completion)
        if (result.isFailure) {
            println("WARN: FanoutDispatcherElement channel full, suspending")
            dispatchChannel.send(completion)
        }
    }

    override suspend fun close() {
        dispatchChannel.close()
        handlers.clear()
        super.close()
    }
}