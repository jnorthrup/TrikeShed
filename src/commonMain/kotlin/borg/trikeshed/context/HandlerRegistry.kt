package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * A generic, compositional handler registry that lives in the CoroutineContext.
 * It maps a key of type K to a handler (a suspend function) of type V.
 * This replaces the verbose, custom MetaSeries "chord sheets".
 *
 * @param K the type of the key used for handler lookup (e.g., EventType, ProtocolType, String).
 * @param V the functional type of the handler itself (e.g., suspend (Request) -> Response).
 */
class HandlerRegistry<K, V : Function<*>>(
    private val handlers: Map<K, V>
) : CoroutineContext.Element {

    override val key: CoroutineContext.Key<*> = Key

    /**
     * Retrieves a handler for the given key.
     */
    fun get(key: K): V? = handlers[key]

    /**
     * Composition operator. When adding a new registry to a context,
     * its handlers overlay the existing ones.
     */
    operator fun plus(other: HandlerRegistry<K, V>): HandlerRegistry<K, V> {
        return HandlerRegistry(handlers + other.handlers)
    }

    companion object Key : CoroutineContext.Key<HandlerRegistry<*, *>>
}

/**
 * A DSL helper to add or update handlers in a coroutine's context.
 */
suspend fun <K, V : Function<*>> withHandlers(
    vararg newHandlers: Pair<K, V>,
    block: suspend CoroutineScope.() -> Unit
) {
    val existingRegistry = coroutineContext[HandlerRegistry.Key] as? HandlerRegistry<K, V>
    val newRegistry = HandlerRegistry(newHandlers.toMap())

    val finalRegistry = existingRegistry?.plus(newRegistry) ?: newRegistry

    withContext(finalRegistry, block)
}

/**
 * Extension to easily access the handler registry from any coroutine context.
 */
inline val <K, V : Function<*>> CoroutineContext.handlerRegistry: HandlerRegistry<K, V>?
    get() = this[HandlerRegistry.Key] as? HandlerRegistry<K, V>
