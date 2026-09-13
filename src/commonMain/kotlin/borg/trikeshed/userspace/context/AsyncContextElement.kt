package borg.trikeshed.userspace.context

/* Compatibility layer: reuse the shared AsyncContextElement and ElementState
   definitions to avoid duplicate interfaces across packages. */

import borg.trikeshed.context.AsyncContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Userspace NioUserspaceElement delegates to canonical implementation.
 */
open class NioUserspaceElement : AsyncContextElement() {
    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.NioUserspaceKey
}
