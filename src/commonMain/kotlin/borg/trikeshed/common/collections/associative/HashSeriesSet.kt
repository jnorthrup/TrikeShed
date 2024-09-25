@file:Suppress("CANDIDATE_CHOSEN_USING_OVERLOAD_RESOLUTION_BY_LAMBDA_ANNOTATION", "UNCHECKED_CAST")

package borg.trikeshed.common.collections.associative

import borg.trikeshed.common.collections.s_
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.combine
import borg.trikeshed.lib.contains
import borg.trikeshed.lib.*
import borg.trikeshed.lib.forEach
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.plus
import borg.trikeshed.lib.size
import borg.trikeshed.lib.`▶`
import kotlin.math.max

typealias HashBucket<T> = Series<T>
typealias Version = Int

interface HashBody<T> : Join<Version, Series<HashBucket<T>>>

class HashSeriesSet<T> : MutableSet<T> {
    private var handle: HashSeriesBody<T> = HashSeriesBody.empty()

    override fun add(element: T): Boolean {
        val oldSize = size
        handle = handle.add(element)
        return size > oldSize
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val oldSize = size
        elements.forEach { add(it) }
        return size > oldSize
    }

    override fun clear() {
        handle = HashSeriesBody.empty()
    }

    override fun remove(element: T): Boolean {
        val oldSize = size
        handle = handle.remove(element)
        return size < oldSize
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        val oldSize = size
        elements.forEach { remove(it) }
        return size < oldSize
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        val oldSize = size
        val elementsSet = elements.toSet()
        val toRemove = handle.toSeries().`▶`.filter { it !in elementsSet }
        toRemove.forEach { remove(it) }
        return size < oldSize
    }

    override fun contains(element: T): Boolean = handle.contains(element)

    override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

    override fun isEmpty(): Boolean = size == 0

    override val size: Int get() = handle.size

    override fun iterator(): MutableIterator<T> = object : MutableIterator<T> {
        private val series = handle.toSeries()
        private var index = 0

        override fun hasNext(): Boolean = index < series.size

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return series[index++]
        }

        override fun remove() {
            if (index == 0) throw IllegalStateException("Call next() before remove()")
            this@HashSeriesSet.remove(series[index - 1])
        }
    }

    fun toSeries(): Series<T> =  combine(handle.b) as Series<T>
}

private class HashSeriesBody<T> private constructor(
    override val a: Version,
    override val b: Series<HashBucket<T>>,
    val size: Int,
    private val loadFactor: Float = 0.75f
) : HashBody<T> {

    companion object {
        fun <T> empty(): HashSeriesBody<T> {

            return HashSeriesBody(0, ( 0 until 16 ).toSeries() α  {_:Int-> EmptySeries as HashBucket<T>}, 0)
        }
    }

    fun add(element: T): HashSeriesBody<T> {
        val index = getBucketIndex(element)
        val bucket:HashBucket<T> = b[index]
        if (bucket.`▶`.contains(element)) return this

        size+1 j   { i:Int ->
            val newBucket =  bucket  * T::class // + element
            val newBuckets = b.toMutableList().apply { set(index, newBucket) }
            return if (size + 1 > loadFactor * b.size) resize(newBuckets.toSeries(), size + 1) else HashSeriesBody(a + 1, newBuckets.toSeries(), size + 1, loadFactor)
        }


    }

    fun contains(element: T): Boolean {
        val bucket = b[getBucketIndex(element)]
        return bucket.contains(element)
    }

    fun remove(element: T): HashSeriesBody<T> {
        val index = getBucketIndex(element)
        val bucket = b[index]
        if (!bucket.contains(element)) return this
        val newBucket = bucket.filter { it != element }
        val newBuckets = b.toMutableList().apply { set(index, newBucket) }
        return HashSeriesBody(a + 1, newBuckets.toSeries(), size - 1, loadFactor)
    }

    private fun getBucketIndex(element: T): Int = (element.hashCode() and 0x7fffffff) % b.size

    private fun resize(buckets: Series<HashBucket<T>>, size: Int): HashSeriesBody<T> {
        val newCapacity = max(16, buckets.size * 2)
        val newBuckets = s_[s_[]] + Series(newCapacity - 1) { s_[] }
        buckets.forEach { bucket ->
            bucket.forEach { element ->
                val index = (element.hashCode() and 0x7fffffff) % newCapacity
                newBuckets[index] = newBuckets[index] + element
            }
        }
        return HashSeriesBody(a + 1, newBuckets, size, loadFactor)
    }

}
