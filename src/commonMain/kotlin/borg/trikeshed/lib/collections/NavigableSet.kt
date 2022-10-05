package borg.trikeshed.lib.collections

interface NavigableSet<T>
    : SortedSet<T> {
    fun descendingSet(): NavigableSet<T>
    fun descendingIterator():Iterator<T>
    override fun iterator():Iterator<T>
    fun subSet(fromElement:T, fromInclusive:Boolean, toElement:T, toInclusive:Boolean): NavigableSet<T>
    fun headSet(toElement:T, inclusive:Boolean): NavigableSet<T>
    fun tailSet(fromElement:T, inclusive:Boolean): NavigableSet<T>
}