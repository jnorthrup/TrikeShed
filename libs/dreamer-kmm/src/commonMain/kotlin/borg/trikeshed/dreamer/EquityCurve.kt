package borg.trikeshed.dreamer

import borg.trikeshed.lib.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Equity curve analysis utilities for [BacktestResult] cycle data.
 *
 * These functions extract and analyze the totalValue series from
 * [CycleResult] to produce per-bar equity metrics: drawdowns, returns,
 * rolling statistics, and trade analysis.
 *
 * All functions are pure projections over [Series]<[CycleResult]>.
 */

/**
 * Extract the equity curve (totalValue at each tick) from cycle results.
 */
fun Series<CycleResult>.equityCurve(): Series<Double> =
    this α { it.totalValue }

/**
 * Compute per-bar returns from the equity curve.
 * Returns a series of length N-1 where each element is the
 * percentage change from bar i to bar i+1.
 */
fun Series<CycleResult>.barReturns(): Series<Double> {
    if (size < 2) return emptySeries()
    return zipWithNext() α { (prev, curr) ->
        if (prev.totalValue > 0.0) (curr.totalValue - prev.totalValue) / prev.totalValue else 0.0
    }
}

/**
 * Compute win rate: fraction of bars with positive totalValue change.
 * A "win" is a bar where totalValue increased from the previous bar.
 */
fun Series<CycleResult>.winRate(): Double {
    if (size < 2) return 0.0
    val returns = barReturns()
    if (returns.isEmpty()) return 0.0
    val wins = returns.view.count { it > 0.0 }
    return wins.toDouble() / returns.size
}

/**
 * Compute profit factor: gross positive returns / gross negative returns.
 * A profit factor > 1.0 means the strategy is profitable.
 * Returns Double.POSITIVE_INFINITY if there are no losses.
 */
fun Series<CycleResult>.profitFactor(): Double {
    if (size < 2) return 0.0
    val returns = barReturns()
    if (returns.isEmpty()) return 0.0
    var grossProfit = 0.0
    var grossLoss = 0.0
    returns.view.forEach { r ->
        if (r > 0.0) grossProfit += r
        else if (r < 0.0) grossLoss += abs(r)
    }
    return if (grossLoss > 0.0) grossProfit / grossLoss
    else if (grossProfit > 0.0) Double.POSITIVE_INFINITY
    else 0.0
}

/**
 * Compute the maximum consecutive losing bars (negative bar returns).
 */
fun Series<CycleResult>.maxConsecutiveLosses(): Int {
    if (size < 2) return 0
    val returns = barReturns()
    if (returns.isEmpty()) return 0
    var current = 0
    var maxStreak = 0
    returns.view.forEach { r ->
        if (r < 0.0) {
            current++
            maxStreak = max(maxStreak, current)
        } else {
            current = 0
        }
    }
    return maxStreak
}

/**
 * Compute the average drawdown (mean of all drawdown-to-recovery episodes).
 * Returns the mean drawdown as a fraction (e.g. 0.05 = 5% average drawdown).
 */
fun Series<CycleResult>.avgDrawdown(): Double {
    if (size < 2) return 0.0
    var peak = get(0).totalValue
    val drawdowns = mutableListOf<Double>()
    view.forEach { cycle ->
        if (cycle.totalValue > peak) {
            peak = cycle.totalValue
        } else if (peak > 0.0) {
            val dd = (peak - cycle.totalValue) / peak
            if (dd > 0.0) drawdowns.add(dd)
        }
    }
    return if (drawdowns.isNotEmpty()) drawdowns.average() else 0.0
}

/**
 * Compute Calmar ratio: annualized return / max drawdown.
 * Uses the totalReturn from the first/last totalValue and the max drawdown
 * computed from the equity curve.
 */
fun Series<CycleResult>.calmarRatio(initialCapital: Double, annualizationFactor: Double = sqrt(252.0)): Double {
    if (size < 2 || initialCapital <= 0.0) return 0.0
    val finalValue = last().totalValue
    val totalReturn = (finalValue - initialCapital) / initialCapital
    val dd = maxDrawdownFromCurve()
    return if (dd > 0.0) totalReturn / dd else if (totalReturn > 0.0) Double.POSITIVE_INFINITY else 0.0
}

/**
 * Compute max drawdown from the equity curve in this series.
 */
fun Series<CycleResult>.maxDrawdownFromCurve(): Double {
    if (size < 2) return 0.0
    var peak = get(0).totalValue
    var maxDD = 0.0
    view.forEach { cycle ->
        if (cycle.totalValue > peak) {
            peak = cycle.totalValue
        } else if (peak > 0.0) {
            val dd = (peak - cycle.totalValue) / peak
            maxDD = max(maxDD, dd)
        }
    }
    return maxDD
}
