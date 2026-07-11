package borg.trikeshed.userspace.context

/* Compatibility layer: reuse the shared (libs/common) AsyncContextElement and ElementState
   definitions to avoid duplicate interfaces across packages. This file provides thin
   subclasses so userspace code can depend on borg.trikeshed.userspace.context
   while the canonical implementations live under borg.trikeshed.context and
   borg.trikeshed.userspace. */

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.context.NioUserspaceElement as CanonicalNioUserspaceElement
import borg.trikeshed.userspace.LiburingElement
import borg.trikeshed.userspace.reactor.FanoutDispatcherElement
import kotlin.coroutines.CoroutineContext

/** Alias to canonical ElementState from borg.trikeshed.context */
typealias ElementLifecycleState = ElementState

/**
 * Subclass the canonical NioUserspaceElement so its key is the userspace key.
 * This ensures ctx[AsyncContextKey.NioUserspaceKey] works correctly.
 */
open class NioUserspaceElement : CanonicalNioUserspaceElement() {
    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.NioUserspaceKey
}

/** Type alias for LiburingElement from the userspace package */
typealias LiburingElement = borg.trikeshed.userspace.LiburingElement

/**
 * Subclass the canonical FanoutDispatcherElement so its key is the userspace key.
 * This ensures ctx[AsyncContextKey.FanoutDispatcherKey] works correctly.
 */
open class FanoutDispatcherElement : FanoutDispatcherElement() {
    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.FanoutDispatcherKey
}