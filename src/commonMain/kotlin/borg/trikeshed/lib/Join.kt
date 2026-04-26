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


        @JvmInline
        value class PairJoin<A, B>(override val pair: Pair<A, B>) : Join<A, B> {
            override val a: A get() = first;
            override val b: B get() = second;
        }

        @JvmInline
        value class EntryJoin<K, V  >( val entry: Map.Entry<K, V>) : Join<K, V> {
            override val a: K get() = entry.key
            override val b: V get() = entry.value
        }

        operator fun <A, B> invoke(pair:Pair<A,B> ) : Join<A, B>  = PairJoin(pair)
        operator fun <A, B> invoke(pair:Map.Entry<A,B> ) : Join<A, B>  = EntryJoin(pair)

        fun <B> emptySeriesOf(): Series<B> = EmptySeries as Series<B>
    }
}

typealias Twin<T> = Join<T, T>

//Twin factory method
fun <T> Twin(a: T, b: T): Twin<T> = a j b


val <A> Join<A, *>.first: A get() = this.a
val <B> Join<*, B>.second: B get() = this.b

/**
 * exactly like "to" for "Join" but with a different (and shorter!) name
 */
infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

