package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.context.Drain
import borg.trikeshed.context.Synchronization
import borg.trikeshed.htx.HtxReactorElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Root NIO supervisor — single coroutine context key hosting all platform providers.
 *
 * Owns the registered providers and their shared cleanup outcome.
 * Platform providers are registered at [open] time and resolved via [service].
 * [reactorSynchronization] chooses reactor state ownership explicitly; NONE
 * requires serialized access by its owner, independently of the host's CPU count.
 */
open class NioSupervisor(
    private val maxConcurrentIo: Int = 10000,
    val reactorSynchronization: Synchronization = Synchronization.MONITOR,
) : CoroutineContext.Element, Drain {
    private val completion = CompletableDeferred<Result<Unit>>()
    /** Completes once [open] has registered and opened the providers; cancelled when that open failed or drain came first. */
    val opened: CompletableJob = Job()
    /** Completes when drain begins; [open] refuses after it. */
    val admission: CompletableJob = Job()
    /** Claimed by the [open] that registers the platform providers, or by [drain] when it comes first. */
    private val registration: CompletableJob = Job()
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

    companion object Key : CoroutineContext.Key<NioSupervisor>
    override val key: CoroutineContext.Key<*> get() = Key

    @PublishedApi
    internal val services = mutableListOf<CoroutineContext.Element>()

    fun register(provider: CoroutineContext.Element) { services.add(provider) }

    inline fun <reified T : CoroutineContext.Element> service(): T? =
        services.firstOrNull { it is T } as? T

    /** Expose the launch-time I/O capability report registered by the platform. */
    fun capabilityReport(): NioCapabilityReport? = service()

    suspend fun open() {
        if (!admission.isActive || !registration.complete()) {
            // Another open registers, or drain came first: wait for that outcome.
            opened.join()
            check(admission.isActive) { "NIO providers are draining or closed" }
            return
        }
        try {
            val providers = platformNioProviders(reactorSynchronization)
            (providers.firstOrNull { it is NioCapabilityReport } as? NioCapabilityReport)?.let { register(it) }
            providers.forEach { if (it !is NioCapabilityReport) register(it) }
            for (provider in services) if (provider is HtxReactorElement) provider.open()
            opened.complete()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                for (provider in services.asReversed()) if (provider is Drain) {
                    runCatching { provider.drain() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                }
                admission.complete()
                completion.complete(Result.failure(failure))
            }
            opened.cancel()
            throw failure
        }
    }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        if (admission.complete()) {
            if (registration.complete()) opened.cancel() else opened.join()
            // A failed open has already drained its providers and recorded its failure.
            if (!completion.isCompleted) {
                var failure: Throwable? = null
                // Consumers are registered after their dependencies. Finish them first;
                // a failed provider must not prevent the remaining resources closing.
                for (provider in services.asReversed()) if (provider is Drain) {
                    try {
                        provider.drain()
                    } catch (caught: Throwable) {
                        if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
                    }
                }
                completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
            }
        }
        completion.await().getOrThrow()
    }

    suspend fun close() = drain()

}

/**
 * Returns the list of platform NIO providers to register in [NioSupervisor.open].
 * Each platform provides an [actual] implementation returning its concrete SPI instances.
 */
expect fun platformNioProviders(reactorSynchronization: Synchronization = Synchronization.MONITOR): List<CoroutineContext.Element>
