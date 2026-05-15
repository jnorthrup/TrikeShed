@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

typealias MetaSeries<I, T> = Join<I, (I) -> T>

/* simplest assoc functions */

/** primary bounds function  */
val <I,T> MetaSeries<I,T>.size: I get() = a

/** index operator for Series */
operator fun <L,T> MetaSeries<L,T>.get(i: L): T = b(i)
//
///**
// * α-conversion over MetaSeries — generic index-space projection.
// *
// * (λx.M[x]) → (λy.M[y])  alpha-conversion in lambda calculus
// * Here: index transform over I → T becomes I → C
// *
// * Unlike the Series-level α which is specialized to Int index,
// * this operates on any MetaSeries<I, X> and remaps the index function.
// *
// * Example:
// *   MetaSeries(10 j { i -> strings[i] }) α { i -> i * 2 }
// *   → MetaSeries(10 j { i -> strings[i * 2] })
// *
// * The transform operates on the INDEX space, not the value space.
// * This is what allows Manifold row types to remap without materializing.
// */
//inline infix fun <I, X, C> MetaSeries<I, X>.α(
//    crossinline xform: (index: I) -> C,
//): MetaSeries<I, C> = a j { i: I -> xform(b(i)) }

/** α
 * (λx.M[x]) → (λy.M[y])	α-conversion
 * https://en.wikipedia.org/wiki/Lambda_calculus
 *
 * in kotlin terms, λ above is a lambda expression and M is a function and the '.' is the body of the lambda
 * therefore the function M is the receiver of the extension function and the lambda expression is the argument
 *
 *  the simplest possible kotlin example of λx.M[x] is
 *  ` { x -> M(x) } ` making the delta symbol into lambda braces and the x into a parameter and the M(x) into the body
 */

inline infix fun <K,X, C, V :MetaSeries<K,X>> V.α(crossinline xform: (X) -> C): MetaSeries<K,C> = size j { i :K-> xform(
    this[i]
) }
