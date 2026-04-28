@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlin.math.min

//package vec

@InternalCoroutinesApi
typealias CircularQueue<T> = CirQlar<T>

/**

stripped down  circular  queue

only mutability is offer(T)

has cheap direct toVect0r with live properties
has more expensive toList/iterator by copy/concat
 */
@InternalCoroutinesApi
open class CirQlar<T>(
    val maxSize: Int,
    val al: Array<Any?> = arrayOfNulls<Any?>(maxSize),
    val lock: Mutex = Mutex(),
    val evict: ((T) -> Unit)? = null,
) /*: AbstractQueue<T>()*/ {

    var tail: Int = 0
    /*override*/ val size: Int get() = min(tail, maxSize)

    val full: Boolean get() = tail >= maxSize

    /*override */  fun offer(e: T): Boolean {
        val i: Int = tail % maxSize
        val tmp: Any? = evict?.run { al.takeIf { it.size < i }?.get(i) }
        al[i] = e

        if (++tail == 2 * maxSize) tail = maxSize
        tmp?.let { t: Any -> evict.invoke(t as T) }
        return true
    }

    fun toList(): List<T> {
        val iterator: Iterator<T> = iterator()
        return List(size) {
            val next = iterator.next()
            next
        }
    }

    /*override*/ fun poll(): T {
        val s = size
        if (s == 0) throw NoSuchElementException("CircularQueue empty")
        val v = toVect0r()
        val first = v.b(0)
        val newSize = s - 1
        // Eagerly materialize remaining elements — toVect0r lambda reads al
        // at invocation time, and we clear al below.
        val remaining = mutableListOf<T>()
        for (i in 1 until s) remaining.add(v.b(i))
        // clear underlying array
        for (i in al.indices) al[i] = null
        // write remaining elements back starting at 0
        for (i in 0 until newSize) {
            al[i] = remaining[i] as Any?
        }
        tail = newSize
        return first
    }
    /*override*/ fun peek(): T {
        val s = size
        if (s == 0) throw NoSuchElementException("CircularQueue empty")
        return toVect0r().b(0)
    }
    /*override*/ fun add(k: T): Boolean = offer(k)
    operator fun CirQlar<T>.plus(k: T): Boolean = offer(k)
    operator fun CirQlar<T>.plusAssign(k: T) {
        offer(k)
    }

    fun toVect0r(): Join<Int, (Int) -> T> = (size j { x: Int ->
        al[if (tail >= maxSize) {
            (tail + x) % maxSize
        } else x] as T
    })

    fun removeAt(index: Int): T {
        val s = size
        if (index < 0 || index >= s) throw IndexOutOfBoundsException("index $index size $s")
        val v = toVect0r()
        val removed = v.b(index)
        val remaining = mutableListOf<T>()
        for (i in 0 until s) if (i != index) remaining.add(v.b(i))
        for (i in al.indices) al[i] = null
        for (i in 0 until remaining.size) {
            al[i] = remaining[i] as Any?
        }
        tail = remaining.size
        return removed
    }

    /*override*/ fun iterator(): MutableIterator<T> = object : MutableIterator<T> {
        var i = 0
        var lastReturnedIndex: Int? = null
        override fun hasNext(): Boolean = i < this@CirQlar.size
        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            val value = toVect0r().b(i)
            lastReturnedIndex = i
            i++
            return value
        }
        override fun remove(): Unit {
            val idx = lastReturnedIndex ?: throw IllegalStateException("next() has not been called or remove() already called")
            removeAt(idx)
            // After removal, next element shifts into idx, so set i = idx to continue correctly
            i = idx
            lastReturnedIndex = null
        }
    }
}

