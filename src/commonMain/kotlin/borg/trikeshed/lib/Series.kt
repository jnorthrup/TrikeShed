@file:Suppress("UNCHECKED_CAST", "ObjectPropertyName")
@file:OptIn(kotlin.experimental.ExperimentalTypeInference::class, ExperimentalUnsignedTypes::class)

package borg.trikeshed.lib

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.meta
import borg.trikeshed.cursor.type
import borg.trikeshed.isam.meta.IOMemento.*
import kotlin.jvm.JvmInline
import kotlin.jvm.JvmName
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

typealias Series<T> = Join<Int, (Int) -> T>

val <T> Series<T>.size: Int get() = a


/** index operator for Series
*/
operator fun <T> Series<T>.get(i: Int): T = b(i)

/**
 * fold for Series
 *
 */
fun <A, B> Series<A>.fold(z: B, f: (acc: B, A) -> B): B {
    var acc = z
    for (i in 0 until size)
        acc = f(acc, this[i])
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

/**splits a range into multiple parts for upstream reindexing utility
 * 0..11 / 3 produces [0..3, 4..7, 8..11].toSeries()
 *
 * in order to dereference these vectors, invert the applied order
 *  val r=(0 until 743 * 347 * 437) / 437 / 347 / 743
 *
 *  the order to access these is the r[..743][..347][..437]
 */
infix operator fun IntRange.div(denominator: Int): Series<IntRange> =
    (this j (last - first + (1 - first)) / denominator).let { (_: IntRange, subSize: Int): Join<IntRange, Int> ->
        denominator j { x: Int ->
            (subSize * x).let { lower ->
                lower..last.coerceAtMost(lower + subSize - 1)
            }
        }
    }

operator fun <T> Series<T>.div(d: Int): Series<Series<T>> = (0 until size) / d α {
    this[it]
}


fun IntArray.binarySearch(i: Int): Int {
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
 * Series->Set */
fun <S> Join<Int, (Int) -> S>.toSet(opt: MutableSet<S>? = null): MutableSet<S> = (
        opt
            ?: LinkedHashSet(size)
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
val <T> Series<T>.`▶`: IterableSeries<T> get() = this as? IterableSeries ?: IterableSeries(this)

infix operator fun <T> IterableSeries<T>.contains(x: Char): Boolean = this.any { x == it }
infix operator fun <T> Series<T>.contains(it: Char): Boolean = this.`▶` contains it


/***
 * IntHeap is a heap of integers
 */
class IntHeap(series: Series<Int>) {
    private var heap: IntArray = IntArray(series.size)
    private var size = 0

    init {
        for (i in series) add(i)
    }

    fun add(i: Int) {
        if (size == heap.size) {
            val newHeap = IntArray(heap.size * 2)
            for (j in heap.indices)
                newHeap[j] = heap[j]
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
fun ByteArray.toSeries(): Series<Byte> = size j { index: Int ->
    if (index < 0) {
        val stall = 1
    }
    get(index)
}

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
fun String.toSeries(): Series<Char> = this.length j this::get
fun CharSequence.toSeries(): Series<Char> = length j ::get
fun ClosedRange<Int>.toSeries(): Series<Int> = (endInclusive - start + 1) j { i: Int -> i + start }
fun <T> Sequence<T>.toSeries(): Series<T> = toList().toSeries()

fun <T> Series<T>.last(): T {
    require(size > 0) { "last() on empty Series" }
    return this[size.dec()]
}

fun <B> Series<B>.isNotEmpty(): Boolean = size < 0
fun <B> Series<B>.first(): B =
    this.get(0) //naming is _a little bit_ confusing with the pair overloads so it stays a function

fun <B> Series<B>.drop(front: Int): Series<B> = get(min(front, size) until size)
fun <B> Series<B>.dropLast(back: Int): Series<B> = get(0 until max(0, size - back))
fun <B> Series<B>.take(exclusiveEnd: Int): Series<B> = get(0 until min(exclusiveEnd, size))

//series foreachIndexed
fun <T> Series<T>.forEachIndexed(action: (index: Int, T) -> Unit): Unit = this.`▶`.forEachIndexed(action)

//series foreach
fun <T> Series<T>.forEach(action: (T) -> Unit): Unit = this.`▶`.forEach(action)

//series map
fun <T, R> Series<T>.map(transform: (T) -> R): List<R> = this.toList().map(transform)


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


fun <T> Series<T>.isEmpty(): Boolean {
    return a == 0
}

fun <T> Series<T>.reversed(): Series<T> {
    val szCapture = size.dec()
    return size j { it: Int -> this.b((szCapture - it)) }
}

open class EmptySeries : Series<Nothing> by 0 j { x: Int -> TODO("undefined") }

fun <T> emptySeries(): Series<T> = EmptySeries() as Series<T>

fun Series<Char>.parseLong(): Long {
//handles +-
    var sign = 1L
    var x = 0
    when (this[0]) {
        '-' -> {
            sign = -1L; x++
        }

        '+' -> x++
    }
    var r = 0L
    while (x < size) {
        r = r * 10 + (this[x] - '0')
        x++
    }
    return r * sign
}


fun Series<Char>.parseIsoDateTime(): kotlinx.datetime.LocalDateTime {
    val year = this[0..3].parseLong().toInt()
    val month = this[5..6].parseLong().toInt()
    val day = this[8..9].parseLong().toInt()
    val hour = this[11..12].parseLong().toInt()
    val minute = this[14..15].parseLong().toInt()
    val second = this[17..18].parseLong().toInt()
    val nanosecond = this[20..26].parseLong().toInt()
    return kotlinx.datetime.LocalDateTime(year, month, day, hour, minute, second, nanosecond)
}

fun Series<Char>.encodeToByteArray(): ByteArray {
//encode unicode chars to bytes using UTF-8
    var x = 0
    val r = ByteArray(size * 3) //trim after
    var spill = 0 //spill cost of unicode encodings
    while (x < size) {
        val c = this[x].code
        when {
            c < 0x80 -> r[x + spill] = c.toByte()
            c < 0x800 -> {
                r[x + spill] = (0xC0 or (c shr 6)).toByte()
                r[x + spill + 1] = (0x80 or (c and 0x3F)).toByte()
                spill++
            }

            else -> {
                r[x + spill] = (0xE0 or (c shr 12)).toByte()
                r[x + spill + 1] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                r[x + spill + 2] = (0x80 or (c and 0x3F)).toByte()
                spill += 2
            }
        }
        x++
    }
    return r.sliceArray(0..(x + spill - 1))
}

//opposite method to build a charSeries from byte[]
fun ByteArray.decodeToChars(): Series<Char> = toSeries().decodeUtf8(/*CharArray(size)*/)


fun Series<Char>.parseIsoDate(): kotlinx.datetime.LocalDate {
    val year = this[0..3].parseLong().toInt()
    val month = this[5..6].parseLong().toInt()
    val day = this[8..9].parseLong().toInt()
    return kotlinx.datetime.LocalDate(year, month, day)
}

fun Series<Char>.asString(upto: Int = Int.MAX_VALUE): String = this.take(upto).encodeToByteArray().decodeToString()

/** parse a double or throw an exception
 *
 * @return Double
 */
fun Series<Char>.parseDouble(): Double {
    var x = 0
    var isNegativeValue = false
    var r = 0.0
    var decimal = false
    var exponentSign: Byte = 1
    var exponentValue: UShort = 0u
    var digitsAfterDecimal: UByte = 0u


    when (this[x]) {
        '-' -> {
            isNegativeValue = true
            x++
        }

        '+' -> {
            isNegativeValue = false
            x++
        }

        else -> isNegativeValue = false
    }

    var afterE = false
    while (x < size) {
        val c: Char = this[x]
        when (c) {
            'E', 'e' -> {
                require(!afterE) { "Invalid second exponent" }
                afterE = true
                x++
                when {
                    this[x] == '-' -> {
                        exponentSign = -1
                        x++
                    }

                    this[x] == '+' -> x++
                }
                while (x < size) {
                    val c1 = this[x]
                    if (c1 !in '0'..'9') throw NumberFormatException("Invalid exponent value")
                    exponentValue = (exponentValue * 10u + (c1 - '0').toUShort()).toUShort()
                    x++
                }
            }

            '.' -> {
                require(!decimal) { "Invalid second decimal point" }
                require(!afterE) { "Invalid decimal point behind exponent" }
                decimal = true
                x++
            }

            in '0'..'9' -> {
                r = r * 10 + (c - '0').toDouble()
                if (decimal) digitsAfterDecimal++
                x++
            }

            else -> throw NumberFormatException("Invalid character at '${c}' ")
        }
    }
    val dneg: Double = if (isNegativeValue && r != 0.0) -1.0 else 1.0
    return dneg * r * 10.0.pow((exponentSign * exponentValue.toInt() - digitsAfterDecimal.toInt()).toDouble())
}

/** parse a double or return null if not a valid double.
 *
 * @return Double?
 */
fun Series<Char>.parseDoubleOrNull(): Double? = try {
    parseDouble()
} catch (e: Throwable) {
    null
}


//  --- ported from Columnar ---
/**
 * Returns a list of pairs built from the elements of `this` array and the [other] array with the same index.
 * The returned list has length of the shortest collection.
 *
 * @sample samples.collections.Iterables.Operations.zipIterable
 */
infix fun <T, R> List<T>.zip(other: Series<R>): List<Join<T, R>> =
    zip(other.`▶`) { a: T, b: R -> a j b }

@JvmName("vvzip2f")
fun <T, O, R> Series<T>.zip(o: Series<O>, f: (T, O) -> R): Join<Int, (Int) -> R> = size j { x: Int -> f(this[x], o[x]) }

@JvmName("vvzip2")
@Suppress("UNCHECKED_CAST")
infix fun <T, O, R : Series2<T, O>> Series<T>.zip(o: Series<O>): R =
    (min(size, o.size) j { x: Int -> (this[x] j o[x]) }) as R

fun <T> Series<T>.commonPrefixWith(other: Series<T>): Series<T> {
    val shortestLength = min(this.size, other.size)
    var i = 0
    while (i < shortestLength && this[i]!!.equals(other[i])) {
        i++
    }
    return this[0..i]

}

fun <T> Series<T>.zipWithNext(): Series<Twin<T>> = size.dec() j { x: Int -> this[x] j this[x + 1] }


