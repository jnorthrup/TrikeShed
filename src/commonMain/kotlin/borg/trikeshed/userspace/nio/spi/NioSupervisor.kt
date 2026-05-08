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
            state = ElementState.ACTIVE
        }
    }
}
