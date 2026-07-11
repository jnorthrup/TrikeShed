package borg.trikeshed.context

import borg.trikeshed.context.AsyncContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Unified userspace element hierarchy. There is ONE [NioUserspaceKey] that
 * resolves the concrete provider (JVM epoll, Linux io_uring, etc.) at
 * open-time. Empty shells like LiburingElement / FanoutDispatcherElement live
 * here only when a platform actually binds them; otherwise they are forward
 * declarations superseded by [NioSupervisor] and the canonical
 * [borg.trikeshed.userspace.LiburingElement] family.
 */
open class NioUserspaceElement : AsyncContextElement() {
    companion object Key : CoroutineContext.Key<NioUserspaceElement>
    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * Liburing family — the actual element lives in
 * [borg.trikeshed.userspace.LiburingElement]. Here only as a forward; no
 * duplicate lifecycle implementation.
 */
typealias LiburingElement = borg.trikeshed.userspace.LiburingElement

/**
 * Fanout dispatcher family — the actual dispatcher lives in
 * [borg.trikeshed.userspace.reactor.FanoutDispatcherElement]. Here only as a
 * forward; no duplicate lifecycle.
 */
typealias FanoutDispatcherElement = borg.trikeshed.userspace.reactor.FanoutDispatcherElement
