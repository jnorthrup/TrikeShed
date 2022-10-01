package borg.trikeshed.lib

/**
 * Joins two things.  same as Pair
 */
interface Join<A, B> {
    val a: A
    val b: B
    operator fun component1(): A = a
    operator fun component2(): B = b

    val pair: Pair<A, B>
        get() = Pair(a, b)

    companion object {
        operator fun <A, B> invoke(a: A, b: B) = object : Join<A, B> {
            override val a: A = a
            override val b: B = b
        } as Join<A, B>
    }
}
operator fun <A, B> A.plus(other: B): Join<A, B> = Join(this, other)
