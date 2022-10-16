@file:Suppress("UNCHECKED_CAST", "ObjectPropertyName")
@file:OptIn(ExperimentalUnsignedTypes::class)

package borg.trikeshed.lib

import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.collections.s_
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

// when catn is a small number under 10 we minimize complexity by using a simple loop

// otherwise we create indexing offsets in an IntArray for catn and use a binary search to find the next element
    require(catn.isNotEmpty()) { "combine requires at least one Series<A>" }
    val frst = catn[0]
    val sz0 = frst.size
    return when (catn.size) {
        0 -> 0 j { TODO() }
        1 -> frst
        2 -> sz0 + catn[1].size j { i ->
            if (i < sz0) frst[i] else catn[1][i - sz0]
        }

        3 -> sz0 + catn[1].size + catn[2].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                else -> catn[2][i - sz0 - catn[1].size]
            }
        }

        4 -> sz0 + catn[1].size + catn[2].size + catn[3].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                else -> catn[3][i - sz0 - catn[1].size - catn[2].size]
            }

        }

        5 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                else -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
            }
        }

        6 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
                else -> catn[5][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size]
            }
        }

        7 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size -> catn[5][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size]
                else -> catn[6][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size]
            }
        }

        8 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size + catn[7].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size -> catn[5][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size -> catn[6][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size]
                else -> catn[7][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size - catn[6].size]
            }
        }

        9 -> sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size + catn[7].size + catn[8].size j { i ->
            when {
                i < sz0 -> frst[i]
                i < sz0 + catn[1].size -> catn[1][i - sz0]
                i < sz0 + catn[1].size + catn[2].size -> catn[2][i - sz0 - catn[1].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size -> catn[3][i - sz0 - catn[1].size - catn[2].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size -> catn[4][i - sz0 - catn[1].size - catn[2].size - catn[3].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size -> catn[5][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size -> catn[6][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size]
                i < sz0 + catn[1].size + catn[2].size + catn[3].size + catn[4].size + catn[5].size + catn[6].size + catn[7].size -> catn[7][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size - catn[6].size]
                else -> catn[8][i - sz0 - catn[1].size - catn[2].size - catn[3].size - catn[4].size - catn[5].size - catn[6].size - catn[7].size]
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
        ).also { hs -> hs.addAll(this.`▶`) }

// Series iterator for use in for loops
operator fun <A> Series<A>.iterator(): Iterator<A> = object : Iterator<A> {
    var i = 0
    override fun hasNext(): Boolean = i < size
    override fun next(): A = this@iterator[i++]
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
val <T> Series<T>.`▶`: IterableSeries<T> get() = this as?  IterableSeries?: IterableSeries(this)

operator fun <T>Series<T>.contains(it: Char): Boolean =it in this.`▶`
operator fun <T>IterableSeries<T>.contains(x: Char): Boolean =this.any { x == it }


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

///**
// * overload unary minus operator for [Series<J2>]  and return the left side of the join
// */
//inline operator fun <A, B, J : Join<A, B>, S : Series<J>, R : Series<A>> S.unaryMinus() = this.let { it ->
//    val (a, b) = it
//    it α { (a, b) ->
//        a
//    }
//}

/** clashes  with  above
 * overload unary minus operator for [Cursor] or similar 2d Series and return the left side of the innermost join
 */
inline operator fun <reified A> Series<Series<Join<A, *>>>.unaryMinus(): Series<Series<A>> =
    this.let { intFunction1Join: Series<Series<Join<A, *>>> ->
        intFunction1Join α { intFunction1Join1: Series<Join<A, *>> ->
            intFunction1Join1 α { aAnyJoin: Join<A, *> ->
                aAnyJoin.a
            }
        }
    }


fun <T> List<T>.toSeries(): Series<T> = size j ::get

fun BooleanArray.toSeries(): Series<Boolean> = size j ::get

 fun ByteArray.toSeries(): Series<Byte> = size j ::get
fun ShortArray.toSeries(): Series<Short> = size j ::get
fun IntArray.toSeries(): Series<Int> = size j ::get
fun LongArray.toSeries(): Series<Long> = size j ::get
fun FloatArray.toSeries(): Series<Float> = size j ::get
fun DoubleArray.toSeries(): Series<Double> = size j ::get
fun CharArray.toSeries(): Series<Char> = size j ::get
fun UByteArray.toSeries(): Series<UByte> = size j ::get
fun UShortArray.toSeries(): Series<UShort> = size j ::get
fun UIntArray.toSeries(): Series<UInt> = size j ::get
fun ULongArray.toSeries(): Series<ULong> = size j ::get
fun String.toSeries(): Series<Char> = length j ::get
fun CharSequence.toSeries() = length j ::get
fun IntProgression.toSeries() =this.toList().toSeries()

 fun <T> Series<T>.last(): T = this[size - 1]
fun <B> Series<B>.isNotEmpty() = size < 0
fun <B> Series<B>.first() =
    this.get(0) //naming is _a little bit_ confusing with the pair overloads so it stays a function

fun <B> Series<B>.drop(front: Int) = get(front until size)


fun <B> Series<B>.take(exclusiveEnd: Int) = get(0 until exclusiveEnd)

//series foreachIndexed
fun <T> Series<T>.forEachIndexed(action: (index: Int, T) -> Unit): Unit = this.`▶`.forEachIndexed(action)

//series foreach
fun <T> Series<T>.forEach(action: (T) -> Unit): Unit = this.`▶`.forEach(action)

//series map
fun <T, R> Series<T>.map(transform: (T) -> R): Series<R> = this.`▶`.map(transform)


/** IsNumerical
 * iterate the meta enum types and check if all are numerical
 *
 * IoByte,,IoShort,IoInt,IoDouble,IoLong   qualify as numerical
 *
 * kotlin enumset is not available in JS
 *
 */
val Cursor.isNumerical: Boolean
    get() = meta.`▶`.all {
        when (it.type) {
            IoByte, IoShort, IoInt, IoFloat, IoDouble, IoLong -> true
            else -> false
        }
    }
val Cursor.isHomoMorphic: Boolean get() = !meta.`▶`.any { it.type != meta[0].type }

operator fun <A> Series<A>.plus(c: Series<A>) = combine(s_[this] as Series<A>, c)

fun <T> Series<T>.isEmpty(): Boolean {return a==0 }

fun <T> Series<T>.reversed(): Series<T> = size j {it: Int ->this.b(size - it - 1)}
