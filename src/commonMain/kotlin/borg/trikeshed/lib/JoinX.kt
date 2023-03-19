@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER")

package borg.trikeshed.lib

import borg.trikeshed.common.collections.s_


/**
 * boilerplate tuples as _INTERFACES_ not classes, for Kotlin Common created by tuplegen script
 */

/**
 * Joins 2 things - a Pair tuple
 */
interface Join2<A1, A2> {
    val a1: A1
    val a2: A2
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    infix operator fun get(index: Int): Any? {
        require(index in 0..1) { "index out of bounds" }
        return when (index) {
            0 -> a1
            1 -> a2
            else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2> invoke(a1: A1, a2: A2): Join2<A1, A2> = object : Join2<A1, A2> {
            override val a1 get() = a1
            override val a2 get() = a2
        }
    }
}

// Tuple2 toString
fun <A1, A2> Join2<A1, A2>.toString(): String = "($a1, $a2, )"

// Tuple2 Series using s_
val <A1, A2> Join2<A1, A2>.iterable: IterableSeries<Any?> get() = s_[a1, a2].`▶`

/**
 * Joins 3 things - a Triple tuple
 */
interface Join3<A1, A2, A3> {
    val a1: A1
    val a2: A2
    val a3: A3
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    infix operator fun get(index: Int): Any? {
        require(index in 0..2) { "index out of bounds" }
        return when (index) {
            0 -> a1
            1 -> a2
            2 -> a3
            else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3> invoke(a1: A1, a2: A2, a3: A3): Join3<A1, A2, A3> = object : Join3<A1, A2, A3> {
            override val a1 = a1
            override val a2 = a2
            override val a3 = a3
        }
    }
}

// Tuple3 toString
fun <A1, A2, A3> Join3<A1, A2, A3>.toString(): String = "($a1, $a2, $a3, )"
inline val <T>   Join3<T, *, *>.first get() = a1
inline val <T>   Join3<*, T, *>.second get() = a2
inline val <T>   Join3<*, *, T>.third get() = a3

// Tuple3 Series using s_
val <A1, A2, A3> Join3<A1, A2, A3>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3].`▶`


/**
 * Joins 4 things - a Quad tuple
 */
interface Join4<A1, A2, A3, A4> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    infix operator fun get(index: Int): Any? {
        require(index in 0..3) { "index out of bounds" }
        return when (index) {
            0 -> a1
            1 -> a2
            2 -> a3
            3 -> a4
            else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4> invoke(a1: A1, a2: A2, a3: A3, a4: A4): Join4<A1, A2, A3, A4> =
            object : Join4<A1, A2, A3, A4> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
            }
    }
}

// Tuple4 toString
fun <A1, A2, A3, A4> Join4<A1, A2, A3, A4>.toString(): String = "($a1, $a2, $a3, $a4, )"

// Tuple4 Series using s_
val <A1, A2, A3, A4> Join4<A1, A2, A3, A4>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4].`▶`

/**
 * Joins 5 things - a Quint tuple
 */
interface Join5<A1, A2, A3, A4, A5> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    infix operator fun get(index: Int): Any? {
        require(index in 0..4) { "index out of bounds" }
        return when (index) {
            0 -> a1
            1 -> a2
            2 -> a3
            3 -> a4
            4 -> a5
            else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
        ): Join5<A1, A2, A3, A4, A5> = object : Join5<A1, A2, A3, A4, A5> {
            override val a1 get() = a1
            override val a2 get() = a2
            override val a3 get() = a3
            override val a4 get() = a4
            override val a5 get() = a5
        }
    }
}

// Tuple5 toString
fun <A1, A2, A3, A4, A5> Join5<A1, A2, A3, A4, A5>.toString(): String = "($a1, $a2, $a3, $a4, $a5, )"

// Tuple5 Series using s_
val <A1, A2, A3, A4, A5> Join5<A1, A2, A3, A4, A5>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5].`▶`

/**
 * Joins 6 things - a Set tuple
 */
interface Join6<A1, A2, A3, A4, A5, A6> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    infix operator fun get(index: Int): Any? {
        require(index in 0..5) { "index out of bounds" }
        return when (index) {
            0 -> a1
            1 -> a2
            2 -> a3
            3 -> a4
            4 -> a5
            5 -> a6
            else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
        ): Join6<A1, A2, A3, A4, A5, A6> = object : Join6<A1, A2, A3, A4, A5, A6> {
            override val a1 get() = a1
            override val a2 get() = a2
            override val a3 get() = a3
            override val a4 get() = a4
            override val a5 get() = a5
            override val a6 get() = a6
        }
    }
}

// Tuple6 toString
fun <A1, A2, A3, A4, A5, A6> Join6<A1, A2, A3, A4, A5, A6>.toString(): String = "($a1, $a2, $a3, $a4, $a5, $a6, )"

// Tuple6 Series using s_
val <A1, A2, A3, A4, A5, A6> Join6<A1, A2, A3, A4, A5, A6>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6].`▶`

/**
 * Joins 7 things - a Sept tuple
 */
interface Join7<A1, A2, A3, A4, A5, A6, A7> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    infix operator fun get(index: Int): Any? {
        require(index in 0..6) { "index out of bounds" }
        return when (index) {
            0 -> a1
            1 -> a2
            2 -> a3
            3 -> a4
            4 -> a5
            5 -> a6
            6 -> a7
            else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
        ): Join7<A1, A2, A3, A4, A5, A6, A7> = object : Join7<A1, A2, A3, A4, A5, A6, A7> {
            override val a1 get() = a1
            override val a2 get() = a2
            override val a3 get() = a3
            override val a4 get() = a4
            override val a5 get() = a5
            override val a6 get() = a6
            override val a7 get() = a7
        }
    }
}

// Tuple7 toString
fun <A1, A2, A3, A4, A5, A6, A7> Join7<A1, A2, A3, A4, A5, A6, A7>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, )"

// Tuple7 Series using s_
val <A1, A2, A3, A4, A5, A6, A7> Join7<A1, A2, A3, A4, A5, A6, A7>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7].`▶`

/**
 * Joins 8 things - a Oct tuple
 */
interface Join8<A1, A2, A3, A4, A5, A6, A7, A8> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    infix operator fun get(index: Int): Any? {
        require(index in 0..7) { "index out of bounds" }; return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; else -> throw IndexOutOfBoundsException(); }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8> invoke(
            a1: A1, a2: A2, a3: A3, a4: A4, a5: A5, a6: A6, a7: A7, a8: A8,
        ): Join8<A1, A2, A3, A4, A5, A6, A7, A8> = object : Join8<A1, A2, A3, A4, A5, A6, A7, A8> {
            override val a1 get() = a1
            override val a2 get() = a2
            override val a3 get() = a3
            override val a4 get() = a4
            override val a5 get() = a5
            override val a6 get() = a6
            override val a7 get() = a7
            override val a8 get() = a8
        }
    }
}

// Tuple8 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8> Join8<A1, A2, A3, A4, A5, A6, A7, A8>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, )"

// Tuple8 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8> Join8<A1, A2, A3, A4, A5, A6, A7, A8>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8].`▶`

/**
 * Joins 9 things - a Non tuple
 */
interface Join9<A1, A2, A3, A4, A5, A6, A7, A8, A9> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    infix operator fun get(index: Int): Any? {
        require(index in 0..8) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9> invoke(
            a1: A1, a2: A2, a3: A3, a4: A4, a5: A5, a6: A6, a7: A7, a8: A8, a9: A9,
        ): Join9<A1, A2, A3, A4, A5, A6, A7, A8, A9> =
            object : Join9<A1, A2, A3, A4, A5, A6, A7, A8, A9> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
            }
    }
}

// Tuple9 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9> Join9<A1, A2, A3, A4, A5, A6, A7, A8, A9>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, )"

// Tuple9 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9> Join9<A1, A2, A3, A4, A5, A6, A7, A8, A9>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9].`▶`

/**
 * Joins 10 things - a Dec tuple
 */
interface Join10<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    infix operator fun get(index: Int): Any? {
        require(index in 0..9) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10> invoke(
            a1: A1, a2: A2, a3: A3, a4: A4, a5: A5, a6: A6, a7: A7, a8: A8, a9: A9, a10: A10,
        ): Join10<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10> =
            object : Join10<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
            }
    }
}

// Tuple10 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10> Join10<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, )"

// Tuple10 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10> Join10<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10].`▶`

/**
 * Joins 11 things - a Undec tuple
 */
interface Join11<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    infix operator fun get(index: Int): Any? {
        require(index in 0..10) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11> invoke(
            a1: A1, a2: A2, a3: A3, a4: A4, a5: A5, a6: A6, a7: A7, a8: A8, a9: A9, a10: A10, a11: A11,
        ): Join11<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11> =
            object : Join11<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
            }
    }
}

// Tuple11 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11> Join11<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, )"

// Tuple11 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11> Join11<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11].`▶`

/**
 * Joins 12 things - a Duodec tuple
 */
interface Join12<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    infix operator fun get(index: Int): Any? {
        require(index in 0..11) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12> invoke(
            a1: A1, a2: A2, a3: A3, a4: A4, a5: A5, a6: A6, a7: A7, a8: A8, a9: A9, a10: A10, a11: A11, a12: A12,
        ): Join12<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12> =
            object : Join12<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
            }
    }
}

// Tuple12 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12> Join12<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, )"

// Tuple12 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12> Join12<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12].`▶`

/**
 * Joins 13 things - a Tredec tuple
 */
interface Join13<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    infix operator fun get(index: Int): Any? {
        require(index in 0..12) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
        ): Join13<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13> =
            object : Join13<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
            }
    }
}

// Tuple13 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13> Join13<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, )"

// Tuple13 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13> Join13<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13].`▶`

/**
 * Joins 14 things - a Quattuordec tuple
 */
interface Join14<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    infix operator fun get(index: Int): Any? {
        require(index in 0..13) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
        ): Join14<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14> =
            object : Join14<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
            }
    }
}

// Tuple14 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14> Join14<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, )"

// Tuple14 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14> Join14<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14].`▶`

/**
 * Joins 15 things - a Quindec tuple
 */
interface Join15<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    infix operator fun get(index: Int): Any? {
        require(index in 0..14) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
        ): Join15<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15> =
            object : Join15<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
            }
    }
}

// Tuple15 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15> Join15<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, )"

// Tuple15 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15> Join15<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15].`▶`

/**
 * Joins 16 things - a Sexdec tuple
 */
interface Join16<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    val a16: A16
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    operator fun component16(): A16 = a16
    infix operator fun get(index: Int): Any? {
        require(index in 0..15) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; 15 -> a16; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
            a16: A16,
        ): Join16<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16> =
            object : Join16<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
                override val a16 get() = a16
            }
    }
}

// Tuple16 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16> Join16<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, $a16, )"

// Tuple16 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16> Join16<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16].`▶`

/**
 * Joins 17 things - a Septendec tuple
 */
interface Join17<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    val a16: A16
    val a17: A17
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    operator fun component16(): A16 = a16
    operator fun component17(): A17 = a17
    infix operator fun get(index: Int): Any? {
        require(index in 0..16) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; 15 -> a16; 16 -> a17; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
            a16: A16,
            a17: A17,
        ): Join17<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17> =
            object : Join17<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
                override val a16 get() = a16
                override val a17 get() = a17
            }
    }
}

// Tuple17 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17> Join17<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, $a16, $a17, )"

// Tuple17 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17> Join17<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17].`▶`

/**
 * Joins 18 things - a Octodec tuple
 */
interface Join18<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    val a16: A16
    val a17: A17
    val a18: A18
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    operator fun component16(): A16 = a16
    operator fun component17(): A17 = a17
    operator fun component18(): A18 = a18
    infix operator fun get(index: Int): Any? {
        require(index in 0..17) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; 15 -> a16; 16 -> a17; 17 -> a18; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
            a16: A16,
            a17: A17,
            a18: A18,
        ): Join18<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18> =
            object : Join18<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
                override val a16 get() = a16
                override val a17 get() = a17
                override val a18 get() = a18
            }
    }
}

// Tuple18 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18> Join18<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, $a16, $a17, $a18, )"

// Tuple18 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18> Join18<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18].`▶`

/**
 * Joins 19 things - a Novemdec tuple
 */
interface Join19<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    val a16: A16
    val a17: A17
    val a18: A18
    val a19: A19
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    operator fun component16(): A16 = a16
    operator fun component17(): A17 = a17
    operator fun component18(): A18 = a18
    operator fun component19(): A19 = a19
    infix operator fun get(index: Int): Any? {
        require(index in 0..18) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; 15 -> a16; 16 -> a17; 17 -> a18; 18 -> a19; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
            a16: A16,
            a17: A17,
            a18: A18,
            a19: A19,
        ): Join19<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19> =
            object : Join19<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
                override val a16 get() = a16
                override val a17 get() = a17
                override val a18 get() = a18
                override val a19 get() = a19
            }
    }
}

// Tuple19 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19> Join19<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, $a16, $a17, $a18, $a19, )"

// Tuple19 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19> Join19<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19].`▶`

/**
 * Joins 20 things - a Vigint tuple
 */
interface Join20<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    val a16: A16
    val a17: A17
    val a18: A18
    val a19: A19
    val a20: A20
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    operator fun component16(): A16 = a16
    operator fun component17(): A17 = a17
    operator fun component18(): A18 = a18
    operator fun component19(): A19 = a19
    operator fun component20(): A20 = a20
    infix operator fun get(index: Int): Any? {
        require(index in 0..19) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; 15 -> a16; 16 -> a17; 17 -> a18; 18 -> a19; 19 -> a20; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
            a16: A16,
            a17: A17,
            a18: A18,
            a19: A19,
            a20: A20,
        ): Join20<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20> =
            object :
                Join20<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
                override val a16 get() = a16
                override val a17 get() = a17
                override val a18 get() = a18
                override val a19 get() = a19
                override val a20 get() = a20
            }
    }
}

// Tuple20 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20> Join20<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, $a16, $a17, $a18, $a19, $a20, )"

// Tuple20 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20> Join20<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20].`▶`

/**
 * Joins 21 things - a Unvigint tuple
 */
interface Join21<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    val a16: A16
    val a17: A17
    val a18: A18
    val a19: A19
    val a20: A20
    val a21: A21
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    operator fun component16(): A16 = a16
    operator fun component17(): A17 = a17
    operator fun component18(): A18 = a18
    operator fun component19(): A19 = a19
    operator fun component20(): A20 = a20
    operator fun component21(): A21 = a21
    infix operator fun get(index: Int): Any? {
        require(index in 0..20) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; 15 -> a16; 16 -> a17; 17 -> a18; 18 -> a19; 19 -> a20; 20 -> a21; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
            a16: A16,
            a17: A17,
            a18: A18,
            a19: A19,
            a20: A20,
            a21: A21,
        ): Join21<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21> =
            object :
                Join21<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
                override val a16 get() = a16
                override val a17 get() = a17
                override val a18 get() = a18
                override val a19 get() = a19
                override val a20 get() = a20
                override val a21 get() = a21
            }
    }
}

// Tuple21 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21> Join21<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, $a16, $a17, $a18, $a19, $a20, $a21, )"

// Tuple21 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21> Join21<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21].`▶`

/**
 * Joins 22 things - a Duovigint tuple
 */
interface Join22<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    val a16: A16
    val a17: A17
    val a18: A18
    val a19: A19
    val a20: A20
    val a21: A21
    val a22: A22
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    operator fun component16(): A16 = a16
    operator fun component17(): A17 = a17
    operator fun component18(): A18 = a18
    operator fun component19(): A19 = a19
    operator fun component20(): A20 = a20
    operator fun component21(): A21 = a21
    operator fun component22(): A22 = a22
    infix operator fun get(index: Int): Any? {
        require(index in 0..21) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; 15 -> a16; 16 -> a17; 17 -> a18; 18 -> a19; 19 -> a20; 20 -> a21; 21 -> a22; else -> throw IndexOutOfBoundsException(); }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
            a16: A16,
            a17: A17,
            a18: A18,
            a19: A19,
            a20: A20,
            a21: A21,
            a22: A22,
        ): Join22<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22> =
            object :
                Join22<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
                override val a16 get() = a16
                override val a17 get() = a17
                override val a18 get() = a18
                override val a19 get() = a19
                override val a20 get() = a20
                override val a21 get() = a21
                override val a22 get() = a22
            }
    }
}

// Tuple22 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22> Join22<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, $a16, $a17, $a18, $a19, $a20, $a21, $a22, )"

// Tuple22 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22> Join22<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21, a22].`▶`

/**
 * Joins 23 things - a  tuple
 */
interface Join23<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23> {
    val a1: A1
    val a2: A2
    val a3: A3
    val a4: A4
    val a5: A5
    val a6: A6
    val a7: A7
    val a8: A8
    val a9: A9
    val a10: A10
    val a11: A11
    val a12: A12
    val a13: A13
    val a14: A14
    val a15: A15
    val a16: A16
    val a17: A17
    val a18: A18
    val a19: A19
    val a20: A20
    val a21: A21
    val a22: A22
    val a23: A23
    operator fun component1(): A1 = a1
    operator fun component2(): A2 = a2
    operator fun component3(): A3 = a3
    operator fun component4(): A4 = a4
    operator fun component5(): A5 = a5
    operator fun component6(): A6 = a6
    operator fun component7(): A7 = a7
    operator fun component8(): A8 = a8
    operator fun component9(): A9 = a9
    operator fun component10(): A10 = a10
    operator fun component11(): A11 = a11
    operator fun component12(): A12 = a12
    operator fun component13(): A13 = a13
    operator fun component14(): A14 = a14
    operator fun component15(): A15 = a15
    operator fun component16(): A16 = a16
    operator fun component17(): A17 = a17
    operator fun component18(): A18 = a18
    operator fun component19(): A19 = a19
    operator fun component20(): A20 = a20
    operator fun component21(): A21 = a21
    operator fun component22(): A22 = a22
    operator fun component23(): A23 = a23
    infix operator fun get(index: Int): Any? {
        require(index in 0..22) { "index out of bounds" }
        return when (index) {
            0 -> a1; 1 -> a2; 2 -> a3; 3 -> a4; 4 -> a5; 5 -> a6; 6 -> a7; 7 -> a8; 8 -> a9; 9 -> a10; 10 -> a11; 11 -> a12; 12 -> a13; 13 -> a14; 14 -> a15; 15 -> a16; 16 -> a17; 17 -> a18; 18 -> a19; 19 -> a20; 20 -> a21; 21 -> a22; 22 -> a23; else -> throw IndexOutOfBoundsException()
        }
    }

    companion object {
        operator fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23> invoke(
            a1: A1,
            a2: A2,
            a3: A3,
            a4: A4,
            a5: A5,
            a6: A6,
            a7: A7,
            a8: A8,
            a9: A9,
            a10: A10,
            a11: A11,
            a12: A12,
            a13: A13,
            a14: A14,
            a15: A15,
            a16: A16,
            a17: A17,
            a18: A18,
            a19: A19,
            a20: A20,
            a21: A21,
            a22: A22,
            a23: A23,
        ): Join23<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23> =
            object :
                Join23<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23> {
                override val a1 get() = a1
                override val a2 get() = a2
                override val a3 get() = a3
                override val a4 get() = a4
                override val a5 get() = a5
                override val a6 get() = a6
                override val a7 get() = a7
                override val a8 get() = a8
                override val a9 get() = a9
                override val a10 get() = a10
                override val a11 get() = a11
                override val a12 get() = a12
                override val a13 get() = a13
                override val a14 get() = a14
                override val a15 get() = a15
                override val a16 get() = a16
                override val a17 get() = a17
                override val a18 get() = a18
                override val a19 get() = a19
                override val a20 get() = a20
                override val a21 get() = a21
                override val a22 get() = a22
                override val a23 get() = a23
            }
    }
}

// Tuple23 toString
fun <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23> Join23<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23>.toString(): String =
    "($a1, $a2, $a3, $a4, $a5, $a6, $a7, $a8, $a9, $a10, $a11, $a12, $a13, $a14, $a15, $a16, $a17, $a18, $a19, $a20, $a21, $a22, $a23, )"

// Tuple23 Series using s_
val <A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23> Join23<A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23>.iterable: IterableSeries<Any?> get() = s_[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21, a22, a23].`▶`

/**extension syntax for JoinX to Join(X+1) like a j b */

inline infix fun <A, B, C> Join<A, B>.x(extends: C) = Join3(a, b, extends)
inline infix fun <A, B, C, D> Join3<A, B, C>.x(extends: D) = Join4(a1, a2, a3, extends)
inline infix fun <A, B, C, D, E> Join4<A, B, C, D>.x(extends: E) = Join5(a1, a2, a3, a4, extends)
inline infix fun <A, B, C, D, E, F> Join5<A, B, C, D, E>.x(extends: F) = Join6(a1, a2, a3, a4, a5, extends)
inline infix fun <A, B, C, D, E, F, G> Join6<A, B, C, D, E, F>.x(extends: G) = Join7(a1, a2, a3, a4, a5, a6, extends)
inline infix fun <A, B, C, D, E, F, G, H> Join7<A, B, C, D, E, F, G>.x(extends: H) =
    Join8(a1, a2, a3, a4, a5, a6, a7, extends)

inline infix fun <A, B, C, D, E, F, G, H, I> Join8<A, B, C, D, E, F, G, H>.x(extends: I) =
    Join9(a1, a2, a3, a4, a5, a6, a7, a8, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J> Join9<A, B, C, D, E, F, G, H, I>.x(extends: J) =
    Join10(a1, a2, a3, a4, a5, a6, a7, a8, a9, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K> Join10<A, B, C, D, E, F, G, H, I, J>.x(extends: K) =
    Join11(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L> Join11<A, B, C, D, E, F, G, H, I, J, K>.x(extends: L) =
    Join12(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M> Join12<A, B, C, D, E, F, G, H, I, J, K, L>.x(extends: M) =
    Join13(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N> Join13<A, B, C, D, E, F, G, H, I, J, K, L, M>.x(extends: N) =
    Join14(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O> Join14<A, B, C, D, E, F, G, H, I, J, K, L, M, N>.x(
    extends: O,
) = Join15(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P> Join15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O>.x(
    extends: P,
) = Join16(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q> Join16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P>.x(
    extends: Q,
) = Join17(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R> Join17<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q>.x(
    extends: R,
) = Join18(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S> Join18<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R>.x(
    extends: S,
) = Join19(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T> Join19<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S>.x(
    extends: T,
) = Join20(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U> Join20<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T>.x(
    extends: U,
) = Join21(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V> Join21<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U>.x(
    extends: V,
) = Join22(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21, extends)

inline infix fun <A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W> Join22<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V>.x(
    extends: W,
) = Join23(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21, a22, extends)
//23 is enough for now
