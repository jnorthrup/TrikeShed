package borg.trikeshed.common.collections.associative

import borg.trikeshed.lib.Join

//delegate a,b to key,value
class Entry<K, V>(key: K, var value: V) : Join<K, V> {
    override val a: K = key
    override val b: V get() = value
    override fun toString(): String = "$pair"
}