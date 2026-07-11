package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext

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
