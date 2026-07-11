package borg.trikeshed.userspace.context

/* Compatibility layer: reuse the shared (libs/common) AsyncContextElement and ElementState
   definitions to avoid duplicate interfaces across packages. This file provides thin
   type aliases so userspace code can depend on borg.trikeshed.userspace.context
   while the canonical implementations live under borg.trikeshed.context and
   borg.trikeshed.userspace. */

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import kotlin.coroutines.CoroutineContext

/** Alias to canonical ElementState from borg.trikeshed.context */
typealias ElementLifecycleState = ElementState

/** Alias to canonical AsyncContextElement implementation */
typealias AsyncContextElement = borg.trikeshed.context.AsyncContextElement

/** Type alias for NioUserspaceElement from the canonical context package */
typealias NioUserspaceElement = borg.trikeshed.context.NioUserspaceElement

/** Type alias for LiburingElement from the userspace package */
typealias LiburingElement = borg.trikeshed.userspace.LiburingElement

/** Type alias for FanoutDispatcherElement from the reactor package */
typealias FanoutDispatcherElement = borg.trikeshed.userspace.reactor.FanoutDispatcherElement