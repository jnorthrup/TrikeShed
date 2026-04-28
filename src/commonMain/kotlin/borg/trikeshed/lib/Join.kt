@file:Suppress("NonAsciiCharacters", "FunctionName", "ObjectPropertyName", "OVERRIDE_BY_INLINE", "UNCHECKED_CAST")

package borg.trikeshed.lib

import kotlin.jvm.JvmInline


/**
 * Joins two things.  Pair semantics but distinct in the symbol naming
 */
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b

    val pair: Pair<A, B> get() = a to b

    /** debugger hack only, violates all common sense */
    val list: List<Any?> get() = (this as? Series<Any?>)?.toList() ?: emptyList()

    companion object {
        //the Join factory method
        operator fun <A, B> invoke(_a: A, _b: B): Join<A, B> = object : Join<A, B> {
            override inline val a: A get() = _a
            override inline val b: B get() = _b
        }

        // PairJoin: stores the two values directly (not a Pair, to avoid PairJoin(pair) recursion)
        // Not @JvmInline since it has 2 fields (value class requires 1 param)
        class PairJoin<A, B>(override val a: A, override val b: B) : Join<A, B> {
            override val pair: Pair<A, B> get() = a to b
        }

        @JvmInline
        value class EntryJoin<K, V  >( val entry: Map.Entry<K, V>) : Join<K, V> {
            override val a: K get() = entry.key
            override val b: V get() = entry.value
        }

        operator fun <A, B> invoke(pair:Pair<A,B> ) : Join<A, B>  = PairJoin(pair.first, pair.second)
        operator fun <A, B> invoke(pair:Map.Entry<A,B> ) : Join<A, B>  = EntryJoin(pair)

        fun <B> emptySeriesOf(): Series<B> = EmptySeries as Series<B>
    }
}

typealias Twin<T> = Join<T, T>

//Twin factory method — routes through autoTwin for densest representation
fun <T> Twin(a: T, b: T): Twin<T> = autoTwin(a, b)

/** Twin factory with an [AutoTwinContext] — use when building many Twins of the same runtime type. */
fun <T> Twin(a: T, b: T, ctx: AutoTwinContext<T>): Twin<T> = ctx.pack(a, b)


val <A> Join<A, *>.first: A get() = this.a
val <B> Join<*, B>.second: B get() = this.b

/**
 * exactly like "to" for "Join" but with a different (and shorter!) name.
 * Uses [Join.Companion.PairJoin] for zero allocation beyond the Pair itself.
 */
infix fun <A, B> A.j(b: B): Join<A, B> = Join(this to b)
