@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.common.collections

import borg.trikeshed.common.collections.HashSeriesSet.Companion.MutableHashSeriesSet
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.iterator
import borg.trikeshed.lib.j
import borg.trikeshed.lib.plus
import borg.trikeshed.lib.size
import borg.trikeshed.lib.`▶`

typealias Bucket<T> = Series<T>

open class HashSeriesSet<T : Any>  : SeriesSet<T> {
     open var buckets: Series<Bucket<T>> = createBuckets(16)
    private var _size: Int = 0

    private fun createBuckets(size: Int): Series<Series<T>> =
        size j { 0 j { throw IndexOutOfBoundsException() } }

    override val a: Int
        get() = TODO("Not yet implemented")
    override val b: (Int) -> T
        get() = TODO("Not yet implemented")

    override val size: Int get() = _size
    override fun isEmpty(): Boolean = _size == 0
    override fun containsAll(elements: Collection<T>): Boolean {
        TODO("Not yet implemented")
    }

    override fun contains(element: T): Boolean {
        val bucketIndex = getBucketIndex(element)
        return buckets[bucketIndex].`▶`.any { it == element }
    }

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var bucketIndex = 0
        private var elementIndex = 0

        override fun hasNext(): Boolean {
            while (bucketIndex < buckets.size) {
                if (elementIndex < buckets[bucketIndex].size) {
                    return true
                }
                bucketIndex++
                elementIndex = 0
            }
            return false
        }

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return buckets[bucketIndex][elementIndex++]
        }
    }

    private fun getBucketIndex(element: T): Int =
        (element.hashCode() and 0x7FFFFFFF) % buckets.size


    companion object {
        class MutableHashSeriesSet<T : Any>(
            private val theSet: HashSeriesSet<T> = HashSeriesSet<T>(),

            ) : MutableSeriesSet<T>, SeriesSet<T> by theSet {

            override val size: Int get() = theSet._size
            var buckets: Series<Bucket<T>>
                get() = theSet.buckets;
                set(value) {
                    theSet.buckets = value
                }

            override fun add(element: T): Boolean {
                if (contains(element)) return false

                val bucketIndex = theSet.getBucketIndex(element)
                theSet.buckets += buckets.size j { i ->
                    if (i == bucketIndex) {
                        buckets[i].size.inc() j { j ->
                            if (j == buckets[i].size) element else buckets[i][j]
                        }
                    } else {
                        buckets[i]
                    }
                }
                theSet._size++

                if (theSet._size > buckets.size * 0.75) {
                    resize()
                }

                return true
            }

            override fun remove(element: T): Boolean {
                val bucketIndex = theSet.getBucketIndex(element)
                val bucket = buckets[bucketIndex]
                val index = bucket.`▶`.indexOf(element)
                if (index != -1) {
                    buckets += buckets.size j { i ->
                        if (i == bucketIndex) {
                            bucket.size.dec() j { j ->
                                when {
                                    j < index -> bucket[j]
                                    j >= index -> bucket[j + 1]
                                    else -> throw IndexOutOfBoundsException()
                                }
                            }
                        } else {
                            buckets[i]
                        }
                    }
                    theSet._size--
                    return true
                }
                return false
            }

            override fun clear() {
                buckets = theSet.createBuckets(16)
                theSet._size = 0
            }

            private fun resize() {
                val oldSeries = buckets as Series<Series<T>>
                buckets = theSet.createBuckets(buckets.size * 2)
                theSet._size = 0

                for (i in 0 until oldSeries.size)
                    for (j in 0 until oldSeries[i].size)
                        add(oldSeries[i][j])

            }
        }
    }
}

interface SeriesSet<T>:Series<T> ,Set<T>

interface MutableSeriesSet<T> : SeriesSet<T> {
    fun add(element: T): Boolean
    fun remove(element: T): Boolean
    fun clear()
}


// Example usage
fun main1() {
    val testSet = MutableHashSeriesSet<Int>()
    testSet.add(10)
    testSet.add(20)
    testSet.add(30)

    println("Set size: ${testSet.size}")
    println("Set contains 20: ${testSet.contains(20)}")
    testSet.remove(20)
    println("Set contains 20 after removal: ${testSet.contains(20)}")

    println("Set contents:")
    for (item in testSet) {
        println(item)
    }

    println("Bucket contents:")
    for (bucket in testSet.buckets) {
        println(bucket.`▶`.toList())
    }
}