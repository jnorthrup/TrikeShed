package borg.trikeshed.lib.intp

/**
 * A selection vector: the ordinals of the rows still alive, ascending. Kernels take the vector
 * and its live length `m` separately, so one buffer serves a whole filter chain without
 * reallocating. This is K's `where` result and DuckDB's SelectionVector.
 */
typealias Selection = IntArray

/** Row comparison against a scalar operand; the kernels branch on it once, outside the loop. */
enum class Comparison { LT, LE, GT, GE, EQ, NE }

/** `0 until n` as a selection. */
fun identitySelection(n: Int): Selection = IntArray(n) { it }
