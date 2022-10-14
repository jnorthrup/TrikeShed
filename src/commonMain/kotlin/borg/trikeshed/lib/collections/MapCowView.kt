@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib.collections

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

//and for Map:
class MapCowView<K, V>(private var kvMap: Map<K, V> = emptyMap()) : Map<K, V>, AbstractMutableMap<K, V>() {
    //keep our inital list until a mutable operation, then replace with .toMutableMap
    private var once: Mutex? = Mutex()

    private var guardFunction: (() -> Unit)? = {
        runBlocking {
            once?.withLock { //thundering herds may all arrive here at once, but only one will get to copy the list
                if (kvMap !is MutableMap<K, V>) {
                    kvMap = kvMap.toMutableMap()
                }
                once = null
                guardFunction = null
            }
        }
    }

    override fun put(key: K, value: V): V? {
        guardFunction?.invoke()
        return (kvMap as MutableMap<K, V>).put(key, value)
    }

    override val size: Int
        get() = kvMap.size

    override fun containsKey(key: K): Boolean {
        return kvMap.containsKey(key)
    }

    override fun containsValue(value: V): Boolean {
        return kvMap.containsValue(value)
    }

    override fun get(key: K): V? {
        return kvMap.get(key)
    }

    override fun isEmpty(): Boolean {
        return kvMap.isEmpty()
    }

    @Deprecated("undefined behavior")
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = kvMap.entries as MutableSet<MutableMap.MutableEntry<K, V>>
}