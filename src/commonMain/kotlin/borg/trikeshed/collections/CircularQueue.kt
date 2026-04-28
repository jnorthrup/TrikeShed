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
        // clear underlying array
        for (i in al.indices) al[i] = null
        // write remaining elements back starting at 0
        for (i in 0 until newSize) {
            al[i] = v.b(i + 1) as Any?
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

    /*override*/ fun iterator(): MutableIterator<T> = object : MutableIterator<T> {
        val v = toVect0r()
        var i = 0
        override fun hasNext(): Boolean = i < v.size
        override fun next(): T = v.b(i++)
        override fun remove(): Unit {
            throw UnsupportedOperationException("remove not supported")
        }
    }
}

