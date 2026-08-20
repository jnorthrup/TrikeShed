package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlin.coroutines.CoroutineContext

/**
 * Root NIO supervisor — single coroutine context key hosting all platform providers.
 *
 * Owns the lifecycle FSM ([AsyncContextElement]) and a service registry.
 * Platform providers are registered at [open] time and resolved via [service].
 */
open class NioSupervisor(
    private val maxConcurrentIo: Int = 10000,
) : AsyncContextElement() {
    private val ioSemaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrentIo)

    suspend fun acquireIo() {
        ioSemaphore.acquire()
    }

    fun tryAcquireIo(): Boolean {
        return ioSemaphore.tryAcquire()
    }

    fun releaseIo() {
        ioSemaphore.release()
    }

    companion object Key : AsyncContextKey<NioSupervisor>()
    override val key: CoroutineContext.Key<*> get() = Key

    @PublishedApi
    internal val services = mutableListOf<CoroutineContext.Element>()

    fun register(provider: CoroutineContext.Element) { services.add(provider) }

    // Bolt: Prevent intermediate List allocations and short-circuit by using firstOrNull { it is T } instead of filterIsInstance<T>().firstOrNull()
    inline fun <reified T : CoroutineContext.Element> service(): T? =
        services.firstOrNull { it is T } as? T

    /** Expose the launch-time I/O capability report registered by the platform. */
    fun capabilityReport(): NioCapabilityReport? = service()

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            val providers = platformNioProviders()
            (providers.firstOrNull { it is NioCapabilityReport } as? NioCapabilityReport)?.let { register(it) }
            providers.forEach { if (it !is NioCapabilityReport) register(it) }
            // Bolt: Prevent intermediate List allocations and short-circuit by using a single forEach loop instead of chained filterIsInstance<T>().filter { ... }
            services.forEach {
                if (it is AsyncContextElement && it.state == ElementState.CREATED) {
                    it.open()
                }
            }
            state = ElementState.ACTIVE
        }
    }

    override suspend fun drain() {
        // Bolt: Prevent intermediate List allocations by using a single forEach loop instead of chained filterIsInstance<T>().filter { ... }
        services.forEach {
            if (it is AsyncContextElement && it.state.isAtLeast(ElementState.OPEN) && it.state.isLessThan(ElementState.DRAINING)) {
                it.drain()
            }
        }
        super.drain()
    }

    override suspend fun close() {
        // Bolt: Prevent intermediate List allocations by using a single forEach loop instead of chained filterIsInstance<T>().filter { ... }
        services.asReversed().forEach {
            if (it is AsyncContextElement && it.state.isLessThan(ElementState.CLOSED)) {
                it.close()
            }
        }
        super.close()
    }
}

/**
 * Returns the list of platform NIO providers to register in [NioSupervisor.open].
 * Each platform provides an [actual] implementation returning its concrete SPI instances.
 */
expect fun platformNioProviders(): List<CoroutineContext.Element>
