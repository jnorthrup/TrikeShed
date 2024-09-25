@file:Suppress("NonAsciiCharacters", "FunctionName", "ObjectPropertyName", "OVERRIDE_BY_INLINE", "UNCHECKED_CAST")

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


        //the Map factory method
        operator fun <A, B> invoke(map: Map<A, B>): Series<Join<A, B>> = object : Series<Join<A, B>> {
            override val a: Int get() = map.size
            override val b: (Int) -> Join<A, B> get() = { map.entries.elementAt(it).let { Join(it.key, it.value) } }
        }

        fun <B> emptySeriesOf(): Series<B> = 0 j { TODO("Empty list is incomplete") }
    }
}

typealias Twin<T> = Join<T, T>

//Twin factory method
inline fun <T> Twin(a: T, b: T): Twin<T> = a j b


typealias Triplet<T> = Join3<T, T, T>

inline val <A> Join<A, *>.first: A get() = this.a
inline val <B> Join<*, B>.second: B get() = this.b

/**
 * exactly like "to" for "Join" but with a different (and shorter!) name
 */
inline infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

