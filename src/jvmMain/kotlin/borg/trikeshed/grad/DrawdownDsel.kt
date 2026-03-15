package borg.trikeshed.grad

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * Computes peak-to-trough drawdown using Dual arithmetic.
 *
 * @param weights Series of weights/values
 * @return Dual(fractionDrawn, gradient) where the last weight is treated as the independent variable
 */
fun drawdown(weights: Series<Double>): Dual {
    if (weights.size == 0) return Dual(0.0, 0.0)

    var peak = Dual.const(weights[0])
    var maxDrawdown = Dual.const(0.0)

    for (i in 0 until weights.size) {
        val weight =
            if (i == weights.size - 1) {
                Dual.variable(weights[i]) // Last element is independent variable
            } else {
                Dual.const(weights[i]) // Others are constants
            }

        if (weight.v > peak.v) {
            peak = weight
        }

        val drawdown = (peak - weight) / peak
        if (drawdown.v > maxDrawdown.v) {
            maxDrawdown = drawdown
        }
    }

    return maxDrawdown
}

/**
 * Computes running-min peak-to-trough drawdown as plain Double.
 *
 * @param equity Series of equity values
 * @return Maximum peak-to-trough drawdown as Double
 */
fun maxDrawdown(equity: Series<Double>): Double {
    if (equity.size == 0) return 0.0

    var peak = equity[0]
    var maxDrawdown = 0.0

    for (i in 0 until equity.size) {
        val value = equity[i]
        if (value > peak) {
            peak = value
        }

        val drawdown = (peak - value) / peak
        if (drawdown > maxDrawdown) {
            maxDrawdown = drawdown
        }
    }

    return maxDrawdown
}
