package borg.trikeshed.duck

import borg.trikeshed.grad.Dual
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.grad.exp as dualExp

/**
 * Computes Exponential Moving Average (EMA) fold over a column.
 *
 * @param col Series of values (cast from Any? to Double)
 * @param span EMA span parameter
 * @return Final EMA value as Double
 */
fun emaFold(
    col: Series<Any?>,
    span: Int,
): Double {
    if (col.size == 0 || span <= 0) return 0.0

    val alpha = 2.0 / (span + 1)
    var ema = (col.get(0) as Number).toDouble()

    for (i in 1 until col.size) {
        val value = (col[i] as Number).toDouble()
        ema = alpha * value + (1 - alpha) * ema
    }

    return ema
}

/**
 * Computes MACD (Moving Average Convergence Divergence) fold.
 *
 * @param col Series of values
 * @param fast Fast EMA span (default: 12)
 * @param slow Slow EMA span (default: 26)
 * @return MACD value (fast EMA - slow EMA)
 */
fun macdFold(
    col: Series<Any?>,
    fast: Int,
    slow: Int,
): Double = emaFold(col, fast) - emaFold(col, slow)

/**
 * Computes soft PnL fold with gradient tracking.
 * Treats the last element as the independent variable (dv=1.0).
 *
 * @param pnl Series of PnL values
 * @return Dual with accumulated PnL and gradient wrt last element
 */
fun softPnlFold(pnl: Series<Any?>): Dual {
    if (pnl.size == 0) return Dual(0.0, 0.0)

    var acc = Dual(0.0, 0.0)

    for (i in 0 until pnl.size) {
        val value = (pnl[i] as Number).toDouble()
        val dv = if (i == pnl.size - 1) 1.0 else 0.0
        acc = acc + Dual(value, dv)
    }

    return acc
}
