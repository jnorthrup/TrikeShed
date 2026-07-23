@file:Suppress("ObjectPropertyName", "FunctionName")

package borg.trikeshed.reduction

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series

/**
 * More-specific `j` overload for `Int j (Int) -> T`. The generic `A.j(b)` cannot infer a
 * lambda's parameter type without an expected type, so `n j { i -> f(i) }` fails to
 * compile when the result lacks an explicit annotation. This overload anchors the lambda
 * to `(Int) -> T` and returns a [Series] directly — exactly the Series-literal grammar
 * used throughout the codebase (`10 j { i -> i * 2 }`).
 */
infix fun <T> Int.j(getter: (Int) -> T): Series<T> = object : Join<Int, (Int) -> T> {
    override val a: Int get() = this@j
    override val b: (Int) -> T get() = getter
}

/**
 * Generates [count] random non-negative integers in a small range, suitable as
 * synthetic sizes for property-based tests. (Not part of kotlin stdlib; provided so
 * `Random(seed).nextInts(n)` resolves in test code.)
 */
fun kotlin.random.Random.nextInts(count: Int): IntArray = IntArray(count) { nextInt(1, 32) }
