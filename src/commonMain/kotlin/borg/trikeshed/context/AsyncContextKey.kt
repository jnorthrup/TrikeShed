package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext

/**
 * Type-safe key for [AsyncContextElement] lookup in [CoroutineContext].
 */
open class AsyncContextKey<E : AsyncContextElement> : CoroutineContext.Key<E>
