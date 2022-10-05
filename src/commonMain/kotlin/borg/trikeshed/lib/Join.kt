package borg.trikeshed.lib

import kotlin.js.JsName

/**
 * Joins two things.  same as Pair but with a different name.
 */
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b

    val pair: Pair<A, B>
        get() = Pair(a, b)

    companion object {
        operator fun <A, B> invoke(a1: A, b1: B) = object : Join<A, B> {
            override val a get() = a1
            override val b get() = b1
        }
    }
}

/**
 * exactly like "to" for "Join" but with a different (and shorter!) name
 */
infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

/**
 * (λx.M[x]) → (λy.M[y])	α-conversion
 * https://en.wikipedia.org/wiki/Lambda_calculus
 * */
infix fun <A, C, B : (A) -> C, V : Series<A>> V.α(m: B): Join<Int, (Int) -> C> = map(m)

fun <T, R, V : Join<Int, (Int) -> T>> V.map(fn: (T) -> R): Join<Int, (Int) -> R> =
    Series(this.size) { it: Int -> fn(b(it)) }

/**
 * provides unbounded access to first and last rows beyond the existing bounds of 0 until size
 */
val <T> Join<Int, (Int) -> T>.infinite: Join<Int, (Int) -> T>
    get() = Int.MAX_VALUE j { x: Int ->
        this.b(
            when {
                x < 0 -> 0
                size <= x -> size.dec()
                else -> x
            }
        )
    }

/**
 * index by enum
 */
operator fun <S, E : Enum<E>> Join<Int, (Int) -> S>.get(e: E): S = get(e.ordinal)

/** Series toList
 * @return an AbstractList<A> of the Series<A>
 */
fun <A> Series<A>.toList(): AbstractList<A> = object : AbstractList<A>() {
    /** kotlin delegation of 'a' to 'size' */
    override val size: Int by ::a

    /** kotlin delegation of 'b' to 'get' */
    override fun get(index: Int): A = b(index)
}

fun <T : Byte> Join<Int, (Int) -> T>.toByteArray(): ByteArray =
    ByteArray(size) { i -> get(i) }

fun <T : Char> Join<Int, (Int) -> T>.toCharArray(): CharArray =
    CharArray(size) { i -> get(i) }

fun <T : Int> Join<Int, (Int) -> T>.toIntArray(): IntArray =
    IntArray(size) { i -> get(i) }

fun <T : Boolean> Join<Int, (Int) -> T>.toBooleanArray(): BooleanArray =
    BooleanArray(size) { i -> get(i) }

fun <T : Long> Join<Int, (Int) -> T>.toLongArray(): LongArray =
    LongArray(size) { i -> get(i) }

fun <T : Float> Join<Int, (Int) -> T>.toFloatArray(): FloatArray =
    FloatArray(size) { i -> get(i) }

fun <T : Double> Join<Int, (Int) -> T>.toDoubleArray(): DoubleArray =
    DoubleArray(size) { i -> get(i) }

fun <T : Short> Join<Int, (Int) -> T>.toShortArray(): ShortArray =
    ShortArray(size) { i -> get(i) }

inline fun <reified T> Join<Int, (Int) -> T>.toArray(): Array<T> =
    Array<T>(size) { i -> get(i) }

fun <T> Array<T>.toSeries(): Join<Int, (Int) -> T> =
    (size j ::get) as Join<Int, (Int) -> T>

val <T> T.rightIdentity: () -> T get() = { this }


infix fun <C, B : (Long) -> C> LongArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

// do all the other primitive arrays

infix fun <C, B : (Int) -> C> IntArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Float) -> C> FloatArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Double) -> C> DoubleArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Short) -> C> ShortArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Byte) -> C> ByteArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Char) -> C> CharArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Any) -> C> Array<Any>.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Boolean) -> C> BooleanArray.α(m: B): Join<Int, (Int) -> C> = this.size j { i: Int -> m(this[i]) }

/**
 * series get by iterable
 */
operator fun <T> Series<T>.get(index: Iterable<Int>): Series<T> {

    val array = IntArray(index.count()) { i -> index.elementAt(i) }
    return this[array]
}

/**
 * series get by Series<Int>
 */
operator fun <T> Series<T>.get(index: Series<Int>): Series<T> {

    val array = IntArray(index.size) { i -> index[i] }; return this[array]
}

/**
 * series get by array
 */
operator fun <T> Series<T>.get(index: IntArray): Series<T> = Series(index.size) { i -> this[index[i]] }
