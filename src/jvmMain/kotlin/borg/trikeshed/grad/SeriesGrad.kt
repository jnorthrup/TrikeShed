package borg.trikeshed.grad

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.lib.*

/**
 * Series-Grad Bridge: Lift Series dimensions into expression space
 *
 * Scaffolds Cursor dimensions (station, temp, etc.) as SFun variables
 * for AD optimization across the entire aggregation graph.
 */

// Lift a Series<T> into Series<SFun<DReal>> for element-wise operations
inline val <T : Number> Series<T>.`↑`: Series<SFun<DReal>>
    get() = size j { i: Int ->
        when (val v = this[i]) {
            is Int -> v.toDouble().`↑`
            is Long -> v.toDouble().`↑`
            is Float -> v.toDouble().`↑`
            is Double -> v.`↑`
            else -> (v.toString().toDoubleOrNull() ?: 0.0).`↑`
        }
    }

// Element-wise Grad operations on Series<SFun>
operator fun Series<SFun<DReal>>.plus(other: Series<SFun<DReal>>): Series<SFun<DReal>> =
    (size j other.size).let { (n, _) ->
        n j { i: Int -> this[i] + other[i] }
    }

operator fun Series<SFun<DReal>>.minus(other: Series<SFun<DReal>>): Series<SFun<DReal>> =
    size j { i: Int -> this[i] - other[i] }

operator fun Series<SFun<DReal>>.times(other: Series<SFun<DReal>>): Series<SFun<DReal>> =
    size j { i: Int -> this[i] * other[i] }

operator fun Series<SFun<DReal>>.div(other: Series<SFun<DReal>>): Series<SFun<DReal>> =
    size j { i: Int -> this[i] / other[i] }

// Aggregate SFun Series
fun Series<SFun<DReal>>.sum(): SFun<DReal> =
    fold(first()) { a, b -> a + b }

fun Series<SFun<DReal>>.mean(): SFun<DReal> =
    sum() / size.`↑`

// Variance, stddev as Grad expressions
fun Series<SFun<DReal>>.variance(): SFun<DReal> {
    val μ = mean()
    return map { (it - μ) * (it - μ) }.sum() / size.`↑`
}

fun Series<SFun<DReal>>.stddev(): SFun<DReal> =
    variance().pow(0.5.`↑`)

// Helper: map over Series<SFun>
inline fun Series<SFun<DReal>>.map(
    crossinline f: (SFun<DReal>) -> SFun<DReal>
): Series<SFun<DReal>> = size j { i: Int -> f(this[i]) }

// Helper: fold over Series<SFun>
inline fun Series<SFun<DReal>>.fold(
    initial: SFun<DReal>,
    crossinline op: (SFun<DReal>, SFun<DReal>) -> SFun<DReal>
): SFun<DReal> {
    if (size == 0) return initial
    var acc = initial
    for (i in 0 until size) {
        acc = op(acc, this[i])
    }
    return acc
}

// Create SFun variables for AD optimization
// e.g., val x = variable("temp")
fun variable(name: String): SVar<DReal> = SVar(DReal, name)

// Bind Series to variables for expression graph
// e.g., val expr = bind(stationTemps, variable("t"))
fun bind(series: Series<Double>, v: SVar<DReal>): Series<SFun<DReal>> =
    series.`↑` α { it * v } // creates expression graph

// Evaluate entire Series<SFun> at binding point — see GradOps.kt for infix eval alias
