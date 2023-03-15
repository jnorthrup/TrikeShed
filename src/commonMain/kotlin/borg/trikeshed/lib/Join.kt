@file:Suppress("NonAsciiCharacters", "FunctionName", "ObjectPropertyName")

package borg.trikeshed.lib


/**
 * Joins two things.  Pair semantics but distinct in the symbol naming
 */
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b

    val pair: Pair<A, B>
        get() = Pair(a, b)

    /** debugger hack only, violates all common sense */
    val list: List<Any?> get() = (this as? Series<out Any?>)?.toList() ?: emptyList()

    companion object {
        //the Join factory method
        operator fun <A, B> invoke(a: A, b: B): Join<A, B> = object : Join<A, B> {
            override inline val a: A get() = a
            override inline val b: B get() = b
        }

        //the Pair factory method
        operator fun <A, B> invoke(pair: Pair<A, B>): Join<A, B> = object : Join<A, B> {
            override val a: A get() = pair.first
            override val b: B get() = pair.second
        }

        //Twin factory method
        inline fun <T> Twin(a: T, b: T): Twin<T> = a j b

        //the Map factory method
        operator fun <A, B> invoke(map: Map<A, B>): Series<Join<A, B>> = object : Series<Join<A, B>> {
            override val a: Int get() = map.size
            override val b: (Int) -> Join<A, B> get() = { map.entries.elementAt(it).let { Join(it.key, it.value) } }
        }

        fun <B> emptySeriesOf(): Series<B> = 0 j { TODO("Empty list is incomplete") }
    }
}

typealias Twin<T> = Join<T, T>

inline val <A> Join<A, *>.first: A get() = this.a
inline val <B> Join<*, B>.second: B get() = this.b

/**
 * exactly like "to" for "Join" but with a different (and shorter!) name
 */
inline infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

/** α
 * (λx.M[x]) → (λy.M[y])	α-conversion
 * https://en.wikipedia.org/wiki/Lambda_calculus
 *
 * in kotlin terms, λ above is a lambda expression and M is a function and the '.' is the body of the lambda
 * therefore the function M is the receiver of the extension function and the lambda expression is the argument
 *
 *  the simplest possible kotlin example of λx.M[x] is
 *  ` { x -> M(x) } ` making the delta symbol into lambda braces and the x into a parameter and the M(x) into the body
 */

inline infix fun <X, C, V : Series<X>> V.α(crossinline xform: (X) -> C): Series<C> = size j { i -> xform(this[i]) }

/** an iterable is converted by full reification to List then lazy transform. */
infix fun <X, C, V : Iterable<X>> V.α(xform: (X) -> C): Series<C> =
    ((this as? List<X>) ?: this.toList()).toSeries() α xform

/** this is an alpha conversion however the type erasure forces inlining here for Arrays as a holdover from java
 *  acquiesence */
inline infix fun <X, C> Array<X>.α(crossinline xform: (X) -> C): Series<C> = size j { i: Int -> xform(this[i]) }


/**
 * provides unbounded access to first and last rows beyond the existing bounds of 0 until size
 */
val <T> Series<T>.infinite: Series<T>
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
    override val size: Int = a
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
    Array(size) { i -> get(i) }

fun <T> Array<T>.toSeries(): Join<Int, (Int) -> T> = size j ::get


//clockwise circle arrow unicode character is ↻ (U+21BB)
//counterclockwise circle arrow  unicode character is  ↺ (U+21BA)

//which one above denotes right identity function?  the clockwise one, so the right identity function is the clockwise circle arrow
//which one above denotes left identity function?  the counterclockwise one, so the left identity function is the counterclockwise circle arrow

//left identity function is the counterclockwise circle arrow

//according to openai Codex:
// in kotlin, val a: ()->B  wraps a B.  in lambda calculus which is left identity ?  in kotlin, a: ()->B  is right identity.  left identity is: val a: ()->B = { b }  right identity is:  val a: ()->B = b

inline val <T> T.leftIdentity: () -> T get() = { this }

/**Left Identity Function */
inline val <T> T.`↺`: () -> T get() = leftIdentity

fun <T> `↻`(t: T): T = t
infix fun <T> T.rightIdentity(t: T): T = `↻`(t)

infix fun <C, B : (Int) -> C> IntArray.α(m: B): Series<C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Long) -> C> LongArray.α(m: B): Series<C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Float) -> C> FloatArray.α(m: B): Series<C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Double) -> C> DoubleArray.α(m: B): Series<C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Short) -> C> ShortArray.α(m: B): Series<C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Byte) -> C> ByteArray.α(m: B): Series<C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Char) -> C> CharArray.α(m: B): Series<C> = this.size j { i: Int -> m(this[i]) }

infix fun <C, B : (Boolean) -> C> BooleanArray.α(m: B): Series<C> = this.size j { i: Int -> m(this[i]) }

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
operator fun <T> Series<T>.get(index: IntRange): Series<T> = Series((index.last + 1) - index.first) { i ->
    require(index.step == 1)
    this[index.first + i]
}

