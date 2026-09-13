package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Root NIO supervisor — single coroutine context key hosting all platform providers.
 *
 * Owns the registered providers and their shared cleanup outcome.
 * Platform providers are registered at [open] time and resolved via [service].
 */
open class NioSupervisor(
    private val maxConcurrentIo: Int = 10000,
) : CoroutineContext.Element {
    private val lifecycle = Mutex()
    private val completion = CompletableDeferred<Result<Unit>>()
    var state = ElementState.CREATED
        private set
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

    inline fun <reified T : CoroutineContext.Element> service(): T? =
        services.firstOrNull { it is T } as? T

    /** Expose the launch-time I/O capability report registered by the platform. */
    fun capabilityReport(): NioCapabilityReport? = service()

    suspend fun open() = lifecycle.withLock {
        check(state < ElementState.DRAINING) { "NIO providers are draining or closed" }
        if (state != ElementState.CREATED) return@withLock
        try {
            val providers = platformNioProviders()
            (providers.firstOrNull { it is NioCapabilityReport } as? NioCapabilityReport)?.let { register(it) }
            providers.forEach { if (it !is NioCapabilityReport) register(it) }
            for (provider in services) if (provider is AsyncContextElement) provider.open()
            state = ElementState.ACTIVE
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                for (provider in services.asReversed()) if (provider is AsyncContextElement) {
                    runCatching { provider.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                }
                state = ElementState.CLOSED
                completion.complete(Result.failure(failure))
            }
            throw failure
        }
    }

    suspend fun drain(): Unit = withContext(NonCancellable) {
        val owner = lifecycle.withLock {
            if (state >= ElementState.DRAINING) false
            else { state = ElementState.DRAINING; true }
        }
        if (owner) {
            var failure: Throwable? = null
            // Consumers are registered after their dependencies. Finish them first;
            // a failed provider must not prevent the remaining resources closing.
            for (provider in services.asReversed()) if (provider is AsyncContextElement) {
                try {
                    if (provider.state >= ElementState.OPEN) provider.drain()
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
                } finally {
                    try {
                        provider.close()
                    } catch (caught: Throwable) {
                        if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
                    }
                }
            }
            lifecycle.withLock { state = ElementState.CLOSED }
            completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
        }
        completion.await().getOrThrow()
    }

    suspend fun close() = drain()

}

/**
 * Returns the list of platform NIO providers to register in [NioSupervisor.open].
 * Each platform provides an [actual] implementation returning its concrete SPI instances.
 */
expect fun platformNioProviders(): List<CoroutineContext.Element>
