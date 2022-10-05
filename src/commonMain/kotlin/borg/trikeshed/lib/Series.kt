package borg.trikeshed.lib

import kotlin.jvm.JvmInline

typealias Series<A> = Join<Int, (Int) -> A>

val <A> Series<A>.size: Int get() = a

/**
 * index operator for Series
 */
operator fun <A> Series<A>.get(i: Int): A = b(i)

/**
 * fold for Series
 *
 */
fun <A, B> Series<A>.fold(z: B, f: (acc: B, A) -> B): B {
    var acc = z
    for (i in 0 until size) {
        acc = f(acc, this[i])
    }
    return acc
}

/**
 * runningfold function for Series (like fold but with the index)
 *
 * because the Series is lazy this is a bit more complicated than it would be for a list
 */
fun <A, B> Series<A>.runningfold(initial: B, f: (acc: B, A, Int) -> B): Series<B> {
    var acc = initial
    return size j { i ->
        acc = f(acc, this[i], i)
        acc
    }
}

/**
 * Binary Search for Series<Comparable>
 *     contract: if the value is in the Series then the index of the value is returned
 *          if the value is not in the Series then the index of the first value greater than the value is returned
 *          if the value is greater than all values in the Series then the size of the Series is returned
 *          if the value is less than all values in the Series then 0 is returned
 *          if the Series is empty then 0 is returned
 *          if the Series is null then 0 is returned
 *          if the value is null then 0 is returned
 */
inline fun Series<Int>.binarySearch(a: Int): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midVal = this[mid]
        val cmp = midVal.compareTo(a)
        when {
            cmp < 0 -> low = mid + 1
            cmp > 0 -> high = mid - 1
            else -> return mid // key found
        }
        if (cmp < 0) {
            low = mid + 1
        } else if (cmp > 0) {
            high = mid - 1
        } else {
            return mid
        }
    }
    return -(low + 1)
}

/**
Series combine (series...)
creates a new Series<A> from the varargs of Series<A> passed in
which is a view of the underlying data, not a copy

the resulting Series<A> is ordered and contains all the elements of catn
in the order they were passed in

@param catn the varargs of Series<A> to combine
 */
fun <A> combine(vararg catn: Series<A>): Series<A> { // combine
// here we perform the fastest possible facade from a collection of Series<A> to a single Series<A>

// when catn is a small number under 4 we minimize complexity by using a simple loop

// otherwise we create indexing offsets in an IntArray for catn and use a binary search to find the next element

    return when (catn.size) {
        0 -> 0 j { TODO() }
        1 -> catn[0]
        2 -> catn[0].size + catn[1].size j { i ->
            if (i < catn[0].size) catn[0][i] else catn[1][i - catn[0].size]
        }

        3 -> catn[0].size + catn[1].size + catn[2].size j { i ->
            when {
                i < catn[0].size -> catn[0][i]
                i < catn[0].size + catn[1].size -> catn[1][i - catn[0].size]
                else -> catn[2][i - catn[0].size - catn[1].size]
            }
        }

        4 -> catn[0].size + catn[1].size + catn[2].size + catn[3].size j { i ->
            when {
                i < catn[0].size -> catn[0][i]
                i < catn[0].size + catn[1].size -> catn[1][i - catn[0].size]
                i < catn[0].size + catn[1].size + catn[2].size -> catn[2][i - catn[0].size - catn[1].size]
                else -> catn[3][i - catn[0].size - catn[1].size - catn[2].size]
            }
        }

        else -> {
            val offsets = IntArray(catn.size)
            var offset = 0
            for (i in catn.indices) {
                offsets[i] = offset
                offset += catn[i].size
            }
            offset j { i ->
                val j = offsets.binarySearch(i)
                if (j >= 0) catn[j][i - offsets[j]] else catn[-j - 2][i - offsets[-j - 2]]
            }
        }
    }
} // combine

fun IntArray.binarySearch(i: Int): Int { // avoid speculative execution stalls here
    // minimize the number of variables and reuse them as much as possible
    // minimize the number of branches

    var low = 0
    var high = size - 1

    while (low <= high) {
        val mid = (low + high) ushr 1
        val midVal = this[mid]
        val cmp = midVal.compareTo(i)
        when {
            cmp < 0 -> low = mid + 1
            cmp > 0 -> high = mid - 1
            else -> return mid // key found
        }
    }
    return -(low + 1)
}

/**
 * Vect0r->Set */
fun <S> Join<Int, (Int) -> S>.toSet(opt: MutableSet<S>? = null): MutableSet<S> = (
    opt
        ?: LinkedHashSet<S>(size)
    ).also { hs -> hs.addAll(this.iterable) }

// Series iterator for use in for loops
operator fun <A> Series<A>.iterator(): Iterator<A> = object : Iterator<A> {
    var i = 0
    override fun hasNext(): Boolean = i < size
    override fun next(): A = this@iterator[i++]
}

// wrap Series as an Iterable
fun <A> Series<A>.asIterable(): Iterable<A> = object : Iterable<A> {
    override fun iterator(): Iterator<A> = this.iterator()
}

@JvmInline
value class IterableSeries<A>(val s: Series<A>) : Iterable<A>, Series<A> by s {
    override fun iterator(): Iterator<A> = s.iterator()
}

/**
 * a macro to wrap as Iterable
 *
 * provides a big bright visible symbol that makes
 * conversions easy to follow along during reading the code
 */
val <T> Series<T>.iterable get() = IterableSeries(this)

/***
 * IntHeap is a heap of integers
 */
class IntHeap(series: Series<Int>) {
    private var heap: IntArray = IntArray(series.size)
    private var size = 0

    init {
        for (i in series) {
            add(i)
        }
    }

    fun add(i: Int) {
        if (size == heap.size) {
            val newHeap = IntArray(heap.size * 2)
            for (j in heap.indices) {
                newHeap[j] = heap[j]
            }
            heap = newHeap
        }
        heap[size] = i
        size++
        var j = size - 1
        while (j > 0) {
            val parent = (j - 1) ushr 1
            if (heap[parent] <= heap[j]) {
                break
            }
            val temp = heap[parent]
            heap[parent] = heap[j]
            heap[j] = temp
            j = parent
        }
    }

    fun remove(): Int {
        val result = heap[0]
        size--
        heap[0] = heap[size]
        var j = 0
        while (true) {
            val left = (j shl 1) + 1
            val right = left + 1
            if (left >= size) {
                break
            }
            val min = if (right >= size || heap[left] <= heap[right]) left else right
            if (heap[j] <= heap[min]) {
                break
            }
            val temp = heap[j]
            heap[j] = heap[min]
            heap[min] = temp
            j = min
        }
        return result
    }

    fun isEmpty(): Boolean = size == 0
}
