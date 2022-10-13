package borg.trikeshed.lib.collections

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

//and for Map:
class MapCowView<K,V>(var list: Map<K,V>) : Map<K,V>, AbstractMutableMap<K,V>() {
    //keep our inital list until a mutable operation, then replace with .toMutableMap
    var once: Mutex? = Mutex()

    var guardFunction:(()->Unit)? = {
        runBlocking {
            once?.withLock { //thundering herds may all arrive here at once, but only one will get to copy the list
                if (list !is MutableMap<K, V>) {
                    list = list.toMutableMap()
                }
                once = null
                guardFunction = null
            }
        }
    }

    override fun put(key: K, value: V): V? {
        guardFunction?.invoke()
        return (list as MutableMap<K,V>).put(key, value)
    }

    override val size: Int
        get() = list.size

    override fun containsKey(key: K): Boolean {
        return list.containsKey(key)
    }

    override fun containsValue(value: V): Boolean {
        return list.containsValue(value)
    }

    override fun get(key: K): V? {
        return list.get(key)
    }

    override fun isEmpty(): Boolean {
        return list.isEmpty()
    }