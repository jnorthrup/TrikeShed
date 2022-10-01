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

