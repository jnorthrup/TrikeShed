@file:Suppress("NOTHING_TO_INLINE", "NonAsciiCharacters", "INLINE_CLASS_DEPRECATED", "FunctionName", "UNCHECKED_CAST")

package borg.trikeshed.lib

/**
 * The base binary composition — a product type with two properties and nothing else.
 */
interface Join<out A, out B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b
    val pair: Pair<A, B> get() = Pair(a, b)

    companion object
}

/**
 * Primitive pairs up to 64 bits pack even when constructed through erased types. A function never packs, so a Series
 * (`size j { ... }`) skips the primitive probe; every pair that does not pack is one class, [Joined], rather than one
 * anonymous class per inlined call site, so the code that reads Joins and Series sees one receiver shape.
 */
inline infix fun <A, B> A.j(b: B): Join<A, B> {
    if (b !is Function<*>) packedPrimitiveJoin(this, b)?.let { return it as Join<A, B> }
    return Joined(this, b)
}

/**
 * The Join [j] builds when its pair does not pack. Its fields are generic, so a primitive is boxed once, at
 * construction, and every read returns that box. Open for array-backed edges such as [ByteArraySeries].
 */
open class Joined<out A, out B>(override val a: A, override val b: B) : Join<A, B>

/** Join factory and literal syntax, retaining the representations selected by [j]. */
object `⋈` {
    inline operator fun <A, B> invoke(a: A, b: B): Join<A, B> = a j b

    inline operator fun <A, B> get(a: A, b: B): Join<A, B> = invoke(a, b)

    inline operator fun <A, B> invoke(pair: Pair<A, B>): Join<A, B> = invoke(pair.first, pair.second)

    /** Infer the oracle's key type from its domain; construction never calls the oracle. */
    operator fun <I, T> invoke(domain: I, oracle: (I) -> T): MetaSeries<I, T> = domain j oracle

    operator fun <I, T> get(domain: I, oracle: (I) -> T): MetaSeries<I, T> = invoke(domain, oracle)

    /** Lazy positional zip with equal bounds, not a relational join or a truncating zip. */
    fun <A, B> zip(left: Series<A>, right: Series<B>): Series2<A, B> {
        require(left.size == right.size) { "Join zip requires equal sizes: ${left.size} != ${right.size}" }
        return left joins right
    }
}

/** Same-typed Join. */
typealias Twin<T> = Join<T, T>

/** Construct a Twin. Routes to densest representation available. */
fun <T> Twin(a: T, b: T): Twin<T> = a j b

// ── Companion helpers ──────────────────────────────────────────

/** Empty Series<T> — zero elements, never accessed. */
fun <T> emptySeriesOf(): Series<T> = 0 j { _: Int -> throw IllegalStateException("empty series") }

/** Zip two same-sized Series into a Series2 (Series<Join<A,B>>). */
infix fun <A, B> Series<A>.joins(other: Series<B>): Series2<A, B> = size j { i -> this[i] j other[i] }

// ── MetaSeries / Series ─────────────────────────────────────────

/** The universal indexed abstraction: a bound/key paired with an index oracle. */
typealias MetaSeries<I, T> = Join<I, (I) -> T>

val <I>  MetaSeries<I, *>.domain get() = a

/** Integer-indexed MetaSeries — the default Series. */
typealias Series<T> = MetaSeries<Int, T>

/** Integer-indexed Series of binary products. */
typealias Series2<A, B> = Series<Join<A, B>>

/**
 * Project the A-side of a Series2: [size] elements (taken now), read lazily. A [ReifiedSplitSeries2] whose two sides
 * both have exactly that size is read through its left side, whose oracle is fetched at every element read (a side
 * whose `b` changes shows the change, as `this[it].a` did), with no Join per element and without evaluating the right
 * side. Any other shape, unequal sides included, reads `this[it].a` as always, so the length and the failures of a
 * mismatch are unchanged.
 */
val <A, B> Series2<A, B>.left: Series<A>
    get() {
        val n = size
        val split = this as? ReifiedSplitSeries2<*, *>
        if (split != null && split.leftSeries.a == n && split.rightSeries.a == n) {
            val side = split.leftSeries as Series<A>
            return n j { i: Int -> side.b(i) }
        }
        return n j { this[it].a }
    }

/** Project the B-side of a Series2, as [left] projects the A-side. */
val <A, B> Series2<A, B>.right: Series<B>
    get() {
        val n = size
        val split = this as? ReifiedSplitSeries2<*, *>
        if (split != null && split.leftSeries.a == n && split.rightSeries.a == n) {
            val side = split.rightSeries as Series<B>
            return n j { i: Int -> side.b(i) }
        }
        return n j { this[it].b }
    }

val <T>  Series<T>.size: Int get() = a

operator fun <I, T> MetaSeries<I, T>.get(key: I): T = b(key)

// ── Comparable Series ───────────────────────────────────────────

interface CSeries<T : Comparable<T>> : Series<T>, Comparable<Series<T>> {
    override fun compareTo(other: Series<T>): Int {
        val n = minOf(size, other.size)
        for (i in 0 until n) {
            val c = this[i].compareTo(other[i])
            if (c != 0) return c
        }
        return size.compareTo(other.size)
    }
}

val <T : Comparable<T>> Series<T>.cpb: CSeries<T>
    get() = object : CSeries<T>, Series<T> by this {}

// ── Projection (α) ─────────────────────────────────────────────

/** Lazy map / projection over a Series. */
inline infix fun <X, C, Domain> MetaSeries<Domain, X>.α(/*crossinline*/ xform: (X) -> C): MetaSeries<Domain, C> =
    a j { i -> xform(this[i]) }

/** Iterable projection. */
inline infix fun <X, C, Subject : Iterable<X>> Subject.α(crossinline xform: (X) -> C): Series<C> {
    val indexed = this as? Series<X>
    val source: Series<X> = when {
        indexed != null -> indexed
        this is List<*> -> (this as List<X>).toSeries()
        else -> toList().toSeries()
    }
    return source α { xform(it) }
}
// ── Left identity / constant anchor ────────────────────────────

/** Returns a constant supplier of this value. */
inline val <T> T.`↺`: () -> T get() = leftIdentity

/** Left identity — a supplier that always returns this value. */
val <T> T.leftIdentity: () -> T get() = { this }

// ── Collection literals ─────────────────────────────────────────

/** List literal: _l[a, b, c] */
object _l {
    operator fun <T> get(vararg t: T): List<T> = listOf(*t)
}

/** Array literal: _a[a, b, c], primitive arrays for primitive elements. */
@OptIn(ExperimentalUnsignedTypes::class)
object _a {
    operator fun get(vararg t: Boolean): BooleanArray = t
    operator fun get(vararg t: Byte): ByteArray = t
    operator fun get(vararg t: UByte): UByteArray = t
    operator fun get(vararg t: Char): CharArray = t
    operator fun get(vararg t: Short): ShortArray = t
    operator fun get(vararg t: UShort): UShortArray = t
    operator fun get(vararg t: Int): IntArray = t
    operator fun get(vararg t: UInt): UIntArray = t
    operator fun get(vararg t: Long): LongArray = t
    operator fun get(vararg t: ULong): ULongArray = t
    operator fun get(vararg t: Float): FloatArray = t
    operator fun get(vararg t: Double): DoubleArray = t
    inline operator fun <reified T> get(vararg t: T): Array<T> = arrayOf(*t)
}

/** Set literal: _s[a, b, c] */
object _s {
    operator fun <T> get(vararg t: T): Set<T> = setOf(*t)
}

/** Series literal: s_[a, b, c] */
object s_ {
    operator fun <T> get(vararg t: T): Series<T> = t.size j { i -> t[i] }
}

// ── Range view ──────────────────────────────────────────────────

/** Range selection as composition, not control flow. */
operator fun <T> Series<T>.get(range: IntRange): Series<T> = range.count() j { i -> this[range.first + i] }
