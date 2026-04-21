package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext

/**
 * Sealed base for all static-key singletons in the structured-async context graph.
 * Subclass via a companion object:
 *   companion object Key : AsyncContextKey<MyElement>()
 */
abstract class AsyncContextKey<E : AsyncContextElement> : CoroutineContext.Key<E>
