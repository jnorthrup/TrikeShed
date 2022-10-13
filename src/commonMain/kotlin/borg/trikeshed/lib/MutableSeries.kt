package borg.trikeshed.lib


/**
 * Mutable Series with operators
 */
interface MutableSeries<T> : AppendableSeries<T> {
    operator fun set(index: Int, item: T)
    fun add(item: T)
    fun add(index: Int, item: T)
    fun removeAt(index: Int): T
    fun remove(item: T): Boolean
    fun clear()

    //+,-,+=,-=, etc
    operator fun plus(item: T): MutableSeries<T>
    operator fun minus(item: T): MutableSeries<T>
    operator fun plusAssign(item: T)
    operator fun minusAssign(item: T)
}