package borg.trikeshed.lib.collections

interface SortedMap<T, U> : Map<T, U> {
    fun firstKey(): T
    fun lastKey(): T
    fun lowerKey(key: T): T?
    fun floorKey(key: T): T?
    fun ceilingKey(key: T): T?
    fun higherKey(key: T): T?
    fun pollFirstEntry(): Map.Entry<T, U>?
    fun pollLastEntry(): Map.Entry<T, U>?
    fun subMap(fromKey: T, toKey: T): SortedMap<T, U>
    fun headMap(toKey: T): SortedMap<T, U>
    fun tailMap(fromKey: T): SortedMap<T, U>
    fun comparator(): Comparator<T>?
}
