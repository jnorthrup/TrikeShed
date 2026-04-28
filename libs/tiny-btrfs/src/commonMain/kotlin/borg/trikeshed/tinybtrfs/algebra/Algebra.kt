@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.tinybtrfs.algebra

/** Binary composition. Root TrikeShed uses identical shape. */
data class Join<A, B>(val a: A, val b: B)

/** Infix smart constructor for Join. */
infix fun <A, B> A.j(b: B): Join<A, B> = Join(this, b)

/** Same-typed Join. */
typealias Twin<T> = Join<T, T>

/** Lazy indexed sequence: size plus index function. */
typealias Series<T> = Join<Int, (Int) -> T>

/** Size accessor for Series. */
val <T> Series<T>.size: Int get() = a

/** Index operator for Series. */
operator fun <T> Series<T>.get(i: Int): T = b(i)
