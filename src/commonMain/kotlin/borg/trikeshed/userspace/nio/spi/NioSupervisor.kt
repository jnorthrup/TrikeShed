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
open class NioSupervisor : AsyncContextElement() {
    companion object Key : AsyncContextKey<NioSupervisor>()
    override val key: CoroutineContext.Key<*> get() = Key

    @PublishedApi
    internal val services = mutableListOf<CoroutineContext.Element>()

    fun register(provider: CoroutineContext.Element) { services.add(provider) }

    inline fun <reified T : CoroutineContext.Element> service(): T? =
        services.filterIsInstance<T>().firstOrNull()

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            super.open()
            platformNioProviders().forEach { register(it) }
            services
                .filterIsInstance<AsyncContextElement>()
                .filter { it.state == ElementState.CREATED }
                .forEach { it.open() }
            state = ElementState.ACTIVE
        }
    }

    override suspend fun drain() {
        services
            .filterIsInstance<AsyncContextElement>()
            .filter { it.state.isAtLeast(ElementState.OPEN) && it.state.isLessThan(ElementState.DRAINING) }
            .forEach { it.drain() }
        super.drain()
    }

    override suspend fun close() {
        services
            .asReversed()
            .filterIsInstance<AsyncContextElement>()
            .filter { it.state.isLessThan(ElementState.CLOSED) }
            .forEach { it.close() }
        super.close()
    }
}

/**
 * Returns the list of platform NIO providers to register in [NioSupervisor.open].
 * Each platform provides an [actual] implementation returning its concrete SPI instances.
 */
expect fun platformNioProviders(): List<CoroutineContext.Element>
