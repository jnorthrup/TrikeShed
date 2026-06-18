@file:Suppress("NOTHING_TO_INLINE", "NonAsciiCharacters", "INLINE_CLASS_DEPRECATED", "FunctionName")

package borg.trikeshed.lib

/**
 * The base binary composition — a product type with two properties and nothing else.
 * Lower memory footprint and better cache working set than any JVM data class.
 */
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b
    val pair: Pair<A, B> get() = Pair(a, b)

    companion object
}

/** Infix constructor grammar — exactly like `to` for Pair, but for Join. */
inline infix fun <A, B> A.j(b: B): Join<A, B> = object : Join<A, B> {
    override val a: A get() = this@j
    override val b: B get() = b
}

/** Same-typed Join. */
typealias Twin<T> = Join<T, T>

/** Construct a Twin. Routes to densest representation available. */
fun <T> Twin(a: T, b: T): Twin<T> = a j b

// ── Companion helpers ──────────────────────────────────────────

/** Empty Series<T> — zero elements, never accessed. */
fun <T> emptySeriesOf(): Series<T> = 0 j { _: Int -> throw IllegalStateException("empty series") }

/** Zip two same-sized Series into a Series2 (Series<Join<A,B>>). */
infix fun <A, B> Series<A>.joins(other: Series<B>): Series2<A, B> =
    size j { i -> this[i] j other[i] }

// ── MetaSeries / Series ─────────────────────────────────────────

/** The universal indexed abstraction: a bound/key paired with an index oracle. */
typealias MetaSeries<I, T> = Join<I, (I) -> T>

val <I>  MetaSeries<I, *>.domain get() = a

/** Integer-indexed MetaSeries — the default Series. */
typealias Series<T> = MetaSeries<Int, T>

/** Series of Joins — the split-storage specialization. */
typealias Series2<A, B> = Series<Join<A, B>>

/** Project the A-side of a Series2. */
val <A, B> Series2<A, B>.left: Series<A> get() = size j { this[it].a }

/** Project the B-side of a Series2. */
val <A, B> Series2<A, B>.right: Series<B> get() = size j { this[it].b }

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
/*inline*/  infix fun <X, C, Domain >    MetaSeries<Domain,X>.α(/*crossinline*/ xform: (X) -> C): MetaSeries<Domain,C> = a j { i -> xform(this[i]) }

/** Iterable projection. */
inline infix fun <X, C, Subject : Iterable<X>> Subject.α(crossinline xform: (X) -> C): Iterable<C> =
    map { xform(it) }

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

/** Array literal: _a[a, b, c] */
object _a {
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
operator fun <T> Series<T>.get(range: IntRange): Series<T> =
    range.count() j { i -> this[range.first + i] }


