package borg.trikeshed.lib.collections

interface SortedSet<T> : Set<T> {
    fun first(): T
    fun last(): T
    fun lower(e: T): T?
    fun floor(e: T): T?
    fun ceiling(e: T): T?
    fun higher(e: T): T?
    fun pollFirst(): T?
    fun pollLast(): T?
    fun subSet(fromElement: T, toElement: T): SortedSet<T>
    fun headSet(toElement: T): SortedSet<T>
    fun tailSet(fromElement: T): SortedSet<T>
    fun comparator(): Comparator<T>?
}

// Language: kotlin
