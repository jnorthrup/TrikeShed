package borg.trikeshed.userspace.nio.channels.spi

import kotlin.coroutines.CoroutineContext

/** OS-reported free physical memory and used swap, in bytes. */
data class HostMemory(val freeBytes: Long, val swapBytes: Long)

interface HostMemoryOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<HostMemoryOperations>
    override val key: CoroutineContext.Key<*> get() = Key
    suspend fun sample(): HostMemory
}
