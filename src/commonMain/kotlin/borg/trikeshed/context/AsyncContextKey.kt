package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext

/**
 * Type-safe key for [CoroutineContext.Element] lookup in [CoroutineContext].
 */
open class AsyncContextKey<E : CoroutineContext.Element> : CoroutineContext.Key<E>
