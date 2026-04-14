@file:Suppress("UNCHECKED_CAST", "NOTHING_TO_", "FunctionName", "ClassName", "NonAsciiCharacters")

package vec.macros

import vec.util._a

interface Join<F, S> : borg.trikeshed.lib.Join<F, S> {
    val first: F
    val second: S

    override val a: F get() = first
    override val b: S get() = second

    companion object {
        operator fun <F, S> invoke(first: F, second: S): Join<F, S> = object : Join<F, S> {
            override val first: F get() = first
            override val second: S get() = second
        }

        operator fun <F, S> invoke(pair: Pair<F, S>): Join<F, S> = object : Join<F, S> {
            override val first: F get() = pair.first
            override val second: S get() = pair.second
        }

        operator fun <F, S> invoke(map: Map<F, S>): Series<Join<F, S>> = object : Series<Join<F, S>> {
            override val first: Int get() = map.size
            override val second: (Int) -> Join<F, S> get() = { map.entries.elementAt(it).let { (k, v) -> Join(k, v) } }
        }
    }
}

interface Tripl3<out F, out S, out T> {
    val first: F
    val second: S
    val third: T

    val triple: Triple<F, S, T>
        get() = Triple(first, second, third)

    operator fun component1(): F = first
    operator fun component2(): S = second
    operator fun component3(): T = third

    companion object {
        inline operator fun <F, S, T> invoke(first: F, second: S, third: T): Tripl3<F, S, T> =
            object : Tripl3<F, S, T> {
                override inline val first get() = first
                override inline val second get() = second
                override inline val third get() = third
            }

        inline operator fun <F, S, T> invoke(p: Triple<F, S, T>): Tripl3<F, S, T> =
            p.let { (f, s, t) -> Tripl3(f, s, t) }
    }
}

typealias Tw1n<X> = Join<X, X>

fun <T> Tw1n(first: T, second: T): Tw1n<T> = first t2 second

@JvmInline
value class Tw1nt(val ia: IntArray) : Tw1n<Int> {
    override inline val first: Int get() = ia[0]
    override inline val second: Int get() = ia[1]
}

@JvmInline
value class Twln(val ia: LongArray) : Tw1n<Long> {
    override inline val first: Long get() = ia[0]
    override inline val second: Long get() = ia[1]
}

@JvmName("twinint")
fun <T : Int> Tw1n(first: T, second: T): Tw1nt = Tw1nt(_a[first, second])

@JvmName("twinlong")
fun <T : Long> Tw1n(first: T, second: T): Twln = Twln(_a[first, second])

@JvmName("unaryMinusTw1n")
operator fun <S> Tw1n<S>.unaryMinus(): Array<S> = _a[first, second]

@JvmName("unaryPlusI")
inline operator fun Tw1n<Int>.unaryPlus(): IntRange = (-this).let { (a, b) -> a..b }

@JvmName("aSInt")
inline infix fun <reified R> Tw1n<Int>.α(noinline f: (Int) -> R): Series<R> = (-this).α(f)

@JvmName("aSDouble")
inline infix fun <reified R> Tw1n<Double>.α(noinline f: (Double) -> R): Series<R> = (-this).α(f)

@JvmName("aSLong")
inline infix fun <reified R> Tw1n<Long>.α(noinline f: (Long) -> R): Series<R> = (-this).α(f)

@JvmName("aSFloat")
inline infix fun <reified R> Tw1n<Float>.α(noinline f: (Float) -> R): Series<R> = (-this).α(f)

@JvmName("aSByte")
inline infix fun <reified R> Tw1n<Byte>.α(noinline f: (Byte) -> R): Series<R> = (-this).α(f)

@JvmName("aSChar")
inline infix fun <reified R> Tw1n<Char>.α(noinline f: (Char) -> R): Series<R> = (-this).α(f)

@JvmName("aSShort")
inline infix fun <reified R> Tw1n<Short>.α(noinline f: (Short) -> R): Series<R> = (-this).α(f)

@JvmName("unaryPlusS")
inline operator fun <T, S : Enum<S>> Tw1n<S>.unaryPlus(): IntRange = (-this).let { (a, b) -> a..b }

@JvmName("unaryPlusE")
inline infix operator fun <T : Enum<T>> Enum<T>.rangeTo(ub: Enum<T>): IntRange = this.ordinal..ub.ordinal

@JvmName("αS")
inline infix fun <reified S, reified R> Tw1n<S>.α(noinline f: (S) -> R): Join<Int, (Int) -> R> = (-this).α(f)

infix fun <F, S> F.t2(s: S): Join<F, S> = Join(this, s)

inline infix fun <reified F, reified S, reified T> Join<F, S>.t3(t: T): Tripl3<F, S, T> =
    let { (f: F, s) -> Tripl3(f, s, t) }

infix fun <F, S, T, P : Pair<F, S>> P.t3(t: T): Tripl3<F, S, T> = let { (a, b) -> Tripl3(a, b, t) }

inline fun <reified F, reified S> Pair<F, S>.reversed(): Join<S, F> = second t2 first

inline infix fun <reified A, reified B, reified C, reified D> Tripl3<A, B, C>.t4(d: D): Qu4d<A, B, C, D> =
    let { (a: A, b: B, c: C) -> Qu4d(a, b, c, d) }

interface Qu4d<F, S, T, Z> {
    val first: F
    val second: S
    val third: T
    val fourth: Z

    data class Quad<F, S, T, Z>(val x: F, val y: S, val z: T, val w: Z)

    val quad: Quad<F, S, T, Z> get() = Quad(first, second, third, fourth)

    operator fun component1(): F = first
    operator fun component2(): S = second
    operator fun component3(): T = third
    operator fun component4(): Z = fourth

    companion object {
        operator fun <F, S, T, Z> invoke(
            first: F,
            second: S,
            third: T,
            fourth: Z,
        ): Qu4d<F, S, T, Z> = object : Qu4d<F, S, T, Z> {
            override inline val first get() = first
            override inline val second get() = second
            override inline val third get() = third
            override inline val fourth get() = fourth
        }

        operator fun <F, S, T, Z> invoke(p: Quad<F, S, T, Z>): Qu4d<F, S, T, Z> = p.let { (f, s, t, z) ->
            Qu4d(f, s, t, z)
        }
    }
}
