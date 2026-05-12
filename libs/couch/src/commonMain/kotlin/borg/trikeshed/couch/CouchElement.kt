package borg.trikeshed.couch

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.handle.CollectionHandle
import borg.trikeshed.couch.runtime.CouchRuntime

class CouchElement(
    val runtime: CouchRuntime = CouchRuntime(),
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<CouchElement>()
    override val key: AsyncContextKey<CouchElement> get() = Key

    private val _collections = mutableMapOf<CharSequence, CollectionHandle>()

    val collections: Map<CharSequence, CollectionHandle> get() = _collections

    fun openCollection(name: CharSequence): CollectionHandle {
        require(state.isAtLeast(ElementState.OPEN)) { "CouchElement must be open to create collections" }
        val handle = CollectionHandle.open()
        _collections[name] = handle
        return handle
    }

    fun collection(name: CharSequence): CollectionHandle? = _collections[name]

    fun activeCollections(): Sequence<Map.Entry<CharSequence, CollectionHandle>> =
        _collections.asSequence().filter { it.value.state != borg.trikeshed.couch.handle.HandleState.CLOSED }

    override suspend fun close() {
        _collections.values.forEach { it.close() }
        _collections.clear()
        super.close()
    }
}
