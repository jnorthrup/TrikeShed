package borg.trikeshed.userspace.context

/* Compatibility layer: reuse the shared AsyncContextElement and ElementState
   definitions to avoid duplicate interfaces across packages. */

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlin.coroutines.CoroutineContext

/** Alias to canonical ElementState from borg.trikeshed.context */
typealias ElementLifecycleState = ElementState

/** Alias to canonical AsyncContextElement implementation */
typealias AsyncContextElement = borg.trikeshed.context.AsyncContextElement

/**
 * Userspace NioUserspaceElement delegates to canonical implementation.
 */
open class NioUserspaceElement : AsyncContextElement() {
    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.NioUserspaceKey
}
