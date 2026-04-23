package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext

/**
 * Concrete open element types for NIO userspace, liburing facade, and fanout dispatcher.
 * Keys are companion singletons so ctx[NioUserspaceElement.Key] resolves the element type-safely.
 */
open class NioUserspaceElement : AsyncContextElement() {
    companion object Key : CoroutineContext.Key<NioUserspaceElement>
    override val key: CoroutineContext.Key<*> get() = Key
}

open class LiburingElement : AsyncContextElement() {
    companion object Key : CoroutineContext.Key<LiburingElement>
    override val key: CoroutineContext.Key<*> get() = Key
}

open class FanoutDispatcherElement : AsyncContextElement() {
    companion object Key : CoroutineContext.Key<FanoutDispatcherElement>
    override val key: CoroutineContext.Key<*> get() = Key
}
