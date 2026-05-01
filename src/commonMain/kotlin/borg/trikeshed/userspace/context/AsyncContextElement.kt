package borg.trikeshed.userspace.context

/* Compatibility layer: reuse the shared (libs/common) AsyncContextElement and ElementState
   definitions to avoid duplicate interfaces across packages. This file provides thin
   aliases and subclasses so userspace code can depend on borg.trikeshed.userspace.context
   while the canonical implementations live under borg.trikeshed.context. */

import borg.trikeshed.context.*
import kotlin.coroutines.CoroutineContext

/** Alias to canonical ElementState from borg.trikeshed.context */
typealias ElementLifecycleState = ElementState

/** Alias to canonical AsyncContextElement implementation */
typealias AsyncContextElement = borg.trikeshed.context.AsyncContextElement

/**
 * Subclass the canonical NioUserspaceElement so callers may extend it inside the
 * userspace package without reimplementing lifecycle semantics.
 */
open class NioUserspaceElement : borg.trikeshed.context.NioUserspaceElement() {
    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.NioUserspaceKey
}

open class FanoutDispatcherElement : borg.trikeshed.context.FanoutDispatcherElement() {
    override val key: CoroutineContext.Key<*> get() = AsyncContextKey.FanoutDispatcherKey
}
