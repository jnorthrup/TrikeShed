package borg.trikeshed.grad

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Wraps a Series<Double> as a Series<Dual>.
 *
 * @param varIdx  the index that should be treated as the independent variable (dv = 1.0).
 *                All other elements are wrapped as constants (dv = 0.0).
 *                Pass -1 (default) to make every element a constant.
 */
fun Series<Double>.asDual(varIdx: Int = -1): Series<Dual> =
    size j { i: Int ->
        if (i == varIdx) Dual.variable(this[i]) else Dual.const(this[i])
    }

/**
 * Extracts the primal (.v) from each Dual in the series.
 */
fun Series<Dual>.values(): Series<Double> = size j { i: Int -> this[i].v }

/**
 * Extracts the tangent (.dv) from each Dual in the series.
 */
fun Series<Dual>.grads(): Series<Double> = size j { i: Int -> this[i].dv }

/**
 * Folds the Series<Double> using dual arithmetic.
 *
 * Each element is wrapped as a constant Dual before being passed to [f].
 *
 * @param init  the initial accumulator value
 * @param f     a binary function expressed in Dual arithmetic
 * @return      the final accumulated Dual (both primal and tangent)
 */
fun Series<Double>.gradFold(init: Dual, f: (Dual, Dual) -> Dual): Dual {
    var acc = init
    for (i in 0 until size) {
        acc = f(acc, Dual.const(this[i]))
    }
    return acc
}
