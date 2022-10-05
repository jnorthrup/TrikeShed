package borg.trikeshed.lib.collections

interface   NavigableMap<T, U> : SortedMap<T, U> {
    fun lowerEntry(key: T): Map.Entry<T, U>?
    fun floorEntry(key: T): Map.Entry<T, U>?
    fun ceilingEntry(key: T): Map.Entry<T, U>?
    fun higherEntry(key: T): Map.Entry<T, U>?
    fun firstEntry(): Map.Entry<T, U>?
    fun lastEntry(): Map.Entry<T, U>?
    fun descendingMap(): NavigableMap<T, U>
    fun navigableKeySet(): NavigableSet<T>
    fun descendingKeySet(): NavigableSet<T>
    fun subMap(fromKey: T, fromInclusive: Boolean, toKey: T, toInclusive: Boolean): NavigableMap<T, U>
    fun headMap(toKey: T, inclusive: Boolean): NavigableMap<T, U>
    fun tailMap(fromKey: T, inclusive: Boolean): NavigableMap<T, U>
}
