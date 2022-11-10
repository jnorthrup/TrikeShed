package borg.trikeshed.lib

/**
 * Joins two things.  Pair semantics but distinct in the symbol naming
 */
interface Join<A, B>{
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b

    val pair: Pair<A, B>
        get() = Pair(a, b)

    companion object {
        //the Join factory method
        operator fun <A, B> invoke(a1: A, b1: B) = object : Join<A, B> {
            override val a:A get() = a1
            override val b:B get() = b1
        }

        //the Series factory method
        operator fun <T> invoke(vararg items: T) = object : Series<T> {
            override val a: Int get() = items.size
            override val b: (Int) -> T get() = items::get
        }

        //the Pair factory method
        operator fun <A, B> invoke(pair: Pair<A, B>) = object : Join<A, B> {
            override val a:A get() = pair.first
            override val b:B get() = pair.second
        }

        //Twin factory method
        fun <T> Twin(a: T, b: T): Twin<T> = object : Twin<T> {
            override val a:T get() = a
            override val b:T get() = b
        }

        //the Map factory method
        operator fun <A, B> invoke(map: Map<A, B>) = object : Series<Join<A, B>> {
            override val a: Int get() = map.size
            override val b: (Int) -> Join<A, B> get() = { map.entries.elementAt(it).let { Join(it.key, it.value) } }
        }

        inline fun <reified B> emptySeriesOf(): Series<B> = 0 j { TODO("Empty list is incomplete") }
    }
}

typealias Twin<T> = Join<T, T>

inline val <reified A> Join<A, *>.first: A get() = this.a
inline val <reified B> Join<*, B>.second: B get() = this.b

/**
 * exactly like "to" for "Join" but with a different (and shorter!) name
 */
infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

/** α
 * (λx.M[x]) → (λy.M[y])	α-conversion
 * https://en.wikipedia.org/wiki/Lambda_calculus
 *
 * in kotlin terms, λ above is a lambda expression and M is a function and the '.' is the body of the lambda
 * therefore the function M is the receiver of the extension function and the lambda expression is the argument
 *
 *  the simplest possible kotlin example of λx.M[x] is
 *  ` { x -> M(x) } ` making the delta symbol into lambda braces and the x into a parameter and the M(x) into the body
 *
 *
 */

infix fun <X, C, V : Series<X> > V.α(xform: (X)->C): Join<Int, (Int) -> C> = size j { i -> xform(this[i]) }
infix fun <X, C, V : List<X> > V.α(xform: (X)->C): Join<Int, (Int) -> C> = size j   { i -> xform(this[i]) }


fun <A, B, C, D> ((A) -> B).alpha(f: (C) -> D): (C) -> D = f
//simple example
//fun main() {
//    val f = { x: Int -> x + 1 }
//    val g = { y: Int -> y * 2 }
//    val h = f.alpha(g)
//    println(h(1))
//    //result is 4
//
//    //for Series type
//    val s = Series(1, 2, 3)
//    val t = { x: Int -> x + 1 }
//    val u = { y: Int -> y * 2 }
//    val v = s.α(t)
//    println(v)
//    //result is [2, 4, 6]
//}


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
 * @return an AbstractList<T> of the Series<T>
 */
fun <T> Series<T>.toList(): AbstractList<T> = object : AbstractList<T>() {
    override val size: Int =a
    override fun get(index: Int): T = b(index)
}

fun Series<Byte>.toArray(): ByteArray =
    ByteArray(size) { i -> get(i) }

fun Series<Char>.toArray(): CharArray =
    CharArray(size) { i -> get(i) }

fun Series<Int>.toArray(): IntArray =
    IntArray(size) { i -> get(i) }

fun Series<Boolean>.toArray(): BooleanArray =
    BooleanArray(size) { i -> get(i) }

fun Series<Long>.toArray(): LongArray =
    LongArray(size) { i -> get(i) }

fun Series<Float>.toArray(): FloatArray =
    FloatArray(size) { i -> get(i) }

fun Series<Double>.toArray(): DoubleArray =
    DoubleArray(size) { i -> get(i) }

fun Series<Short>.toArray(): ShortArray =
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

/**
 * series get by intRange
 */
operator fun <T> Series<T>.get(index: IntRange): Series<T> = Series(index.count()) { i -> this[index.first + i] }

