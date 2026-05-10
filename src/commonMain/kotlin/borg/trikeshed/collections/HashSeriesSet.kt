@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections

import borg.trikeshed.collections.HashSeriesSet.Companion.MutableHashSeriesSet
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.iterator
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.plus

typealias Bucket<T> = Series<T>

open class HashSeriesSet<T : Any>  : SeriesSet<T> {
     open var buckets: Series<Bucket<T>> = createBuckets(16)
   var _size: Int = 0

   fun createBuckets(size: Int): Series<Series<T>> =
        size j { 0 j { throw IndexOutOfBoundsException() } }

    override val a: Int
        get() = _size
    override val b: (Int) -> T
        get() = { index: Int ->
            fun find(): T {
                if (index < 0 || index >= _size) throw IndexOutOfBoundsException()
                var remaining = index
                var bi = 0
                while (bi < buckets.size) {
                    val bucket = buckets[bi]
                    if (remaining < bucket.size) return bucket[remaining]
                    remaining -= bucket.size
                    bi++
                }
                throw IndexOutOfBoundsException()
            }
            find()
        }

    override val size: Int get() = _size
    override fun isEmpty(): Boolean = _size == 0
    override fun containsAll(elements: Collection<T>): Boolean {
        return elements.all { contains(it) }
    }

    override fun contains(element: T): Boolean {
        val bucketIndex = getBucketIndex(element)
        return buckets[bucketIndex].view.any { it == element }
    }

    override fun iterator(): Iterator<T> = object : Iterator<T> {
       var bucketIndex = 0
       var elementIndex = 0

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

   fun getBucketIndex(element: T): Int =
        (element.hashCode() and 0x7FFFFFFF) % buckets.size


    companion object {
        class MutableHashSeriesSet<T : Any>(
           val theSet: HashSeriesSet<T> = HashSeriesSet<T>(),

            ) : MutableSeriesSet<T>, SeriesSet<T> by theSet {

            override val size: Int get() = theSet._size
            var buckets: Series<Bucket<T>>
                get() = theSet.buckets;
                set(value) {
                    theSet.buckets = value
                }

            override fun add(element: T): Boolean {
                if (  contains(element)) return false

                val bucketIndex = theSet.getBucketIndex(element)
                val newBuckets: Series<Series<T>> = buckets.size j { i: Int ->
                    if (i == bucketIndex) {
                        buckets[i].size.inc() j { j: Int ->
                            if (j == buckets[i].size) element else buckets[i][j]
                        }
                    } else {
                        buckets[i]
                    }
                }
                buckets = newBuckets
                theSet._size++

                if (theSet._size > buckets.size * 0.75) {
                    resize()
                }

                return true
            }

            override fun remove(element: T): Boolean {
                val bucketIndex: Int = theSet.getBucketIndex(element)
                val bucket: Bucket<T> = buckets[bucketIndex]
                val index = bucket.view.indexOf(element)
                if (index != -1) {
                    val newBuckets: Series<Series<T>> = buckets.size j { i: Int ->
                        if (i == bucketIndex) {
                            bucket.size.dec() j { j: Int ->
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
                    buckets = newBuckets
                    theSet._size--
                    return true
                }
                return false
            }

            override fun clear() {
                buckets = theSet.createBuckets(16)
                theSet._size = 0
            }

           fun resize() {
                val oldSeries: Series<Series<T>> = buckets
                buckets = theSet.createBuckets(buckets.size * 2)
                theSet._size = 0

                for (i in 0 until oldSeries.size)
                    for (j in 0 until oldSeries[i].size)
                        add(oldSeries[i][j])

            }
        }
    }
}

interface SeriesSet<T>: Series<T>,Set<T>

interface MutableSeriesSet<T> : SeriesSet<T> {
    fun add(element: T): Boolean
    fun remove(element: T): Boolean
    fun clear()
}


// Example usage
fun main1() {
    val testSet =
        MutableHashSeriesSet<Int>()
    testSet.add(10)
    testSet.add(20)
    testSet.add(30)

    println("Set size: ${testSet.size}")
    println("Set contains 20: ${testSet.contains(20)}")
    testSet.remove(20)
    println("Set contains 20 after removal: ${testSet.contains(20)}")

    println("Set contents:")
    for (item in testSet)
        println(item)

    println("Bucket contents:")
    for (bucket: Bucket<Int> in testSet.buckets) println(bucket.view.toList())
}
