@file:Suppress("NonAsciiCharacters", "ObjectPropertyName")

/**
 * DrawdownDsel.kt — Drawdown calculus and risk metrics DSEL
 *
 * Provides differentiable drawdown calculations for pretesting and paper-testing
 * trading strategies using Kotlingrad automatic differentiation.
 *
 * Core concepts:
 *   - Drawdown: Peak-to-trough decline in portfolio value
 *   - Max Drawdown (MDD): Largest peak-to-trough decline
 *   - Calmar Ratio: Annual return / Max Drawdown
 *   - Ulcer Index: RMS of drawdowns (pain metric)
 *   - Recovery Factor: Net Profit / Max Drawdown
 */

package borg.trikeshed.grad

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.lib.*

// -- Drawdown Series Calculation --

/**
 * Calculate drawdown series from equity curve.
 * Drawdown at index i = (peak[i] - equity[i]) / peak[i]
 * where peak[i] = max(equity[0..i])
 */
val Series<Double>.drawdownSeries: Series<Double>
    get() {
        if (size == 0) return 0 j { _: Int -> 0.0 }

        var peak = this[0]
        return size j { i: Int ->
            val equity = this[i]
            if (equity > peak) peak = equity
            if (peak == 0.0) 0.0 else (peak - equity) / peak
        }
    }

/**
 * Differentiable drawdown series for SFun expressions.
 */
fun Series<SFun<DReal>>.drawdownSeries(): Series<SFun<DReal>> {
    if (size == 0) return 0 j { _: Int -> DReal.wrap(0.0) }

    val peaks = mutableListOf<SFun<DReal>>()
    var peak = this[0]
    for (i in 0 until size) {
        val equity = this[i]
        peak = if (i == 0) equity else peak.max(equity)
        peaks.add(peak)
    }

    return size j { i: Int ->
        val peakVal = peaks[i]
        val equity = this[i]
        (peakVal - equity) / peakVal
    }
}


// -- Maximum Drawdown --

/**
 * Maximum drawdown value from equity curve.
 */
val Series<Double>.maxDrawdown: Double
    get() {
        val dd = drawdownSeries
        var max = 0.0
        for (i in 0 until dd.size) {
            if (dd[i] > max) max = dd[i]
        }
        return max
    }

/**
 * Differentiable maximum drawdown.
 */
fun Series<SFun<DReal>>.maxDrawdown(): SFun<DReal> {
    val dd = drawdownSeries()
    var max = dd[0]
    for (i in 1 until dd.size) {
        max = max.max(dd[i])
    }
    return max
}


// -- Drawdown Duration --

/**
 * Calculate current drawdown duration (bars in drawdown).
 */
val Series<Double>.drawdownDuration: Series<Int>
    get() {
        if (size == 0) return 0 j { _: Int -> 0 }

        var peakIdx = 0
        return size j { i: Int ->
            if (this[i] > this[peakIdx]) {
                peakIdx = i
                0
            } else {
                i - peakIdx
            }
        }
    }

/**
 * Maximum drawdown duration.
 */
val Series<Double>.maxDrawdownDuration: Int
    get() {
        val durations = drawdownDuration
        var max = 0
        for (i in 0 until durations.size) {
            if (durations[i] > max) max = durations[i]
        }
        return max
    }


// -- Ulcer Index --

/**
 * Ulcer Index: RMS of drawdowns.
 */
val Series<Double>.ulcerIndex: Double
    get() {
        val dd = drawdownSeries
        var sumSquares = 0.0
        for (i in 0 until dd.size) {
            sumSquares += dd[i] * dd[i]
        }
        return kotlin.math.sqrt(sumSquares / dd.size)
    }

/**
 * Differentiable Ulcer Index.
 */
fun Series<SFun<DReal>>.ulcerIndex(): SFun<DReal> {
    val dd = drawdownSeries()
    var sumSquares: SFun<DReal> = DReal.wrap(0.0)
    for (i in 0 until dd.size) {
        sumSquares = sumSquares + dd[i] * dd[i]
    }
    return (sumSquares / size.lift).pow(0.5.lift)
}


// -- Calmar Ratio --

/**
 * Calmar Ratio: Annualized return / Max Drawdown.
 */
fun Series<Double>.calmarRatio(periodsPerYear: Int = 252): Double {
    if (size < 2) return 0.0
    val mdd = maxDrawdown
    if (mdd == 0.0) return Double.POSITIVE_INFINITY

    val totalReturn = (this[size - 1] - this[0]) / this[0]
    val years = size.toDouble() / periodsPerYear
    val annualizedReturn = kotlin.math.pow(1 + totalReturn, 1.0 / years) - 1

    return annualizedReturn / mdd
}

/**
 * Differentiable Calmar Ratio.
 */
fun Series<SFun<DReal>>.calmarRatio(periodsPerYear: Int = 252): SFun<DReal> {
    require(size >= 2) { "Calmar ratio requires at least 2 data points" }
    val mdd = maxDrawdown()
    val totalReturn = (this[size - 1] - this[0]) / this[0]
    val years = size.lift / periodsPerYear.lift
    val annualizedReturn = (1.0.lift + totalReturn).pow(1.0.lift / years) - 1.0.lift
    return annualizedReturn / mdd
}


// -- Recovery Factor --

/**
 * Recovery Factor: Net Profit / Max Drawdown.
 */
fun Series<Double>.recoveryFactor(): Double {
    if (size < 2) return 0.0
    val mdd = maxDrawdown
    if (mdd == 0.0) return Double.POSITIVE_INFINITY

    val netProfit = this[size - 1] - this[0]
    return netProfit / mdd
}

/**
 * Differentiable Recovery Factor.
 */
fun Series<SFun<DReal>>.recoveryFactor(): SFun<DReal> {
    require(size >= 2) { "Recovery factor requires at least 2 data points" }
    val mdd = maxDrawdown()
    val netProfit = this[size - 1] - this[0]
    return netProfit / mdd
}


// -- Pain Index --

/**
 * Pain Index: Average drawdown over the period.
 */
val Series<Double>.painIndex: Double
    get() {
        val dd = drawdownSeries
        var sum = 0.0
        for (i in 0 until dd.size) {
            sum += dd[i]
        }
        return sum / dd.size
    }

/**
 * Differentiable Pain Index.
 */
fun Series<SFun<DReal>>.painIndex(): SFun<DReal> {
    val dd = drawdownSeries()
    var sum: SFun<DReal> = DReal.wrap(0.0)
    for (i in 0 until dd.size) {
        sum = sum + dd[i]
    }
    return sum / size.lift
}


// -- Optimal F Position Sizing --

/**
 * Optimal f: Position sizing based on drawdown constraints.
 */
data class OptimalF(
    val fraction: Double,
    val winRate: Double,
    val winLossRatio: Double
)

/**
 * Calculate optimal position sizing fraction based on historical trades.
 */
fun Series<Double>.optimalF(maxRisk: Double = 0.25): OptimalF {
    var wins = 0
    var losses = 0
    var sumWins = 0.0
    var sumLosses = 0.0

    for (i in 0 until size) {
        val pnl = this[i]
        if (pnl > 0) {
            wins++
            sumWins += pnl
        } else if (pnl < 0) {
            losses++
            sumLosses += -pnl
        }
    }

    val p = wins.toDouble() / size
    val q = 1.0 - p
    val avgWin = if (wins > 0) sumWins / wins else 0.0
    val avgLoss = if (losses > 0) sumLosses / losses else 1.0
    val r = if (avgLoss > 0) avgWin / avgLoss else 0.0

    val kelly = if (r > 0) (r * p - q) / r else 0.0
    val fraction = (kelly * 0.5).coerceIn(0.0, maxRisk)

    return OptimalF(fraction, p, r)
}


// -- Pretesting Contracts --

/**
 * Pretesting configuration for strategy validation.
 */
data class PretestConfig(
    val maxDrawdown: Double = 0.20,
    val minCalmarRatio: Double = 2.0,
    val minRecoveryFactor: Double = 3.0,
    val maxUlcerIndex: Double = 0.10,
    val minPeriods: Int = 100,
    val periodsPerYear: Int = 252
)

/**
 * Pretesting result with pass/fail metrics.
 */
data class PretestResult(
    val passed: Boolean,
    val metrics: Map<String, Double>,
    val failures: List<String>,
    val equityCurve: Series<Double>
) {
    fun summary(): String = buildString {
        appendLine("Pretest Result: ${if (passed) "✅ PASSED" else "❌ FAILED"}")
        appendLine("Metrics:")
        metrics.forEach { (k, v) ->
            appendLine("  $k: ${String.format("%.4f", v)}")
        }
        if (failures.isNotEmpty()) {
            appendLine("Failures:")
            failures.forEach { f -> appendLine("  - $f") }
        }
    }
}

/**
 * Run pretesting validation on equity curve.
 */
fun Series<Double>.pretest(config: PretestConfig = PretestConfig()): PretestResult {
    val failures = mutableListOf<String>()
    val metrics = mutableMapOf<String, Double>()

    val mdd = maxDrawdown
    val calmar = calmarRatio(config.periodsPerYear)
    val recovery = recoveryFactor()
    val ulcer = ulcerIndex
    val pain = painIndex

    metrics["maxDrawdown"] = mdd
    metrics["calmarRatio"] = if (calmar.isInfinite()) Double.MAX_VALUE else calmar
    metrics["recoveryFactor"] = if (recovery.isInfinite()) Double.MAX_VALUE else recovery
    metrics["ulcerIndex"] = ulcer
    metrics["painIndex"] = pain
    metrics["totalReturn"] = (this[size - 1] - this[0]) / this[0]

    if (mdd > config.maxDrawdown) {
        failures.add("Max drawdown ${String.format("%.2f%%", mdd * 100)} > ${config.maxDrawdown * 100}%")
    }

    if (calmar.isFinite() && calmar < config.minCalmarRatio) {
        failures.add("Calmar ratio ${String.format("%.2f", calmar)} < ${config.minCalmarRatio}")
    }

    if (recovery.isFinite() && recovery < config.minRecoveryFactor) {
        failures.add("Recovery factor ${String.format("%.2f", recovery)} < ${config.minRecoveryFactor}")
    }

    if (ulcer > config.maxUlcerIndex) {
        failures.add("Ulcer index ${String.format("%.4f", ulcer)} > ${config.maxUlcerIndex}")
    }

    if (size < config.minPeriods) {
        failures.add("Insufficient periods: $size < ${config.minPeriods}")
    }

    return PretestResult(
        passed = failures.isEmpty(),
        metrics = metrics,
        failures = failures,
        equityCurve = this
    )
}


// -- Paper Testing Contracts --

/**
 * Paper testing configuration for simulated trading.
 */
data class PaperTestConfig(
    val initialCapital: Double = 100000.0,
    val commissionRate: Double = 0.001,
    val slippagePct: Double = 0.0005,
    val positionSizePct: Double = 0.10,
    val maxPositions: Int = 10,
    val periodsPerYear: Int = 252
)

/**
 * Trade record for paper testing.
 */
data class Trade(
    val entryIndex: Int,
    val exitIndex: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val pnl: Double,
    val pnlPct: Double,
    val isLong: Boolean
)

/**
 * Paper testing result with trade log and performance metrics.
 */
data class PaperTestResult(
    val equityCurve: Series<Double>,
    val trades: List<Trade>,
    val totalReturn: Double,
    val annualizedReturn: Double,
    val sharpeRatio: Double,
    val maxDrawdown: Double,
    val winRate: Double,
    val profitFactor: Double
) {
    fun summary(): String = buildString {
        appendLine("Paper Test Result")
        appendLine("Total Return: ${String.format("%.2f%%", totalReturn * 100)}")
        appendLine("Annualized Return: ${String.format("%.2f%%", annualizedReturn * 100)}")
        appendLine("Sharpe Ratio: ${String.format("%.2f", sharpeRatio)}")
        appendLine("Max Drawdown: ${String.format("%.2f%%", maxDrawdown * 100)}")
        appendLine("Win Rate: ${String.format("%.2f%%", winRate * 100)}")
        appendLine("Profit Factor: ${String.format("%.2f", profitFactor)}")
        appendLine("Total Trades: ${trades.size}")
    }
}

/**
 * Run paper testing simulation with signals.
 */
fun paperTest(
    prices: Series<Double>,
    signals: Series<Int>,
    config: PaperTestConfig = PaperTestConfig()
): PaperTestResult {
    require(prices.size == signals.size) { "Prices and signals must have same length" }

    val trades = mutableListOf<Trade>()
    val equity = mutableListOf<Double>()

    var cash = config.initialCapital
    var position = 0.0
    var entryPrice = 0.0
    var entryIdx = -1

    equity.add(cash)

    for (i in 0 until prices.size) {
        val price = prices[i]
        val signal = signals[i]

        val targetPosition = (cash * config.positionSizePct) / price

        if (signal == 1 && position == 0.0) {
            val execPrice = price * (1 + config.slippagePct)
            val qty = targetPosition * (1 - config.commissionRate)

            position = qty
            entryPrice = execPrice
            entryIdx = i
            cash -= qty * execPrice
        }

        if ((signal == -1 || position > 0) && position > 0) {
            val shouldExit = signal == -1 ||
                (i > entryIdx && (price - entryPrice) / entryPrice < -0.10)

            if (shouldExit && i > entryIdx) {
                val execPrice = price * (1 - config.slippagePct)
                val proceeds = position * execPrice * (1 - config.commissionRate)
                val pnl = proceeds - (position * entryPrice)
                val pnlPct = (execPrice - entryPrice) / entryPrice

                trades.add(
                    Trade(
                        entryIndex = entryIdx,
                        exitIndex = i,
                        entryPrice = entryPrice,
                        exitPrice = execPrice,
                        quantity = position,
                        pnl = pnl,
                        pnlPct = pnlPct,
                        isLong = true
                    )
                )

                cash += proceeds
                position = 0.0
                entryIdx = -1
            }
        }

        val mtmEquity = cash + position * price
        equity.add(mtmEquity)
    }

    val equitySeries = equity.size j { i: Int -> equity[i] }
    val totalReturn = (equitySeries[equitySeries.size - 1] - config.initialCapital) / config.initialCapital

    val years = prices.size.toDouble() / config.periodsPerYear
    val annualizedReturn = kotlin.math.pow(1 + totalReturn, 1.0 / years) - 1

    var sumReturn = 0.0
    var count = 0
    for (i in 1 until equitySeries.size) {
        val r = (equitySeries[i] - equitySeries[i - 1]) / equitySeries[i - 1]
        sumReturn += r
        count++
    }
    val avgReturn = sumReturn / count

    var sumVar = 0.0
    for (i in 1 until equitySeries.size) {
        val r = (equitySeries[i] - equitySeries[i - 1]) / equitySeries[i - 1]
        sumVar += (r - avgReturn) * (r - avgReturn)
    }
    val variance = sumVar / count
    val sharpe = if (variance > 0) avgReturn / kotlin.math.sqrt(variance) * kotlin.sqrt(252.0) else 0.0

    var winningTrades = 0
    var losingTrades = 0
    var grossProfit = 0.0
    var grossLoss = 0.0

    for (trade in trades) {
        if (trade.pnl > 0) {
            winningTrades++
            grossProfit += trade.pnl
        } else if (trade.pnl < 0) {
            losingTrades++
            grossLoss += -trade.pnl
        }
    }

    val winRate = if (trades.isNotEmpty()) winningTrades.toDouble() / trades.size else 0.0
    val profitFactor = if (grossLoss > 0) grossProfit / grossLoss else Double.POSITIVE_INFINITY

    return PaperTestResult(
        equityCurve = equitySeries,
        trades = trades,
        totalReturn = totalReturn,
        annualizedReturn = annualizedReturn,
        sharpeRatio = sharpe,
        maxDrawdown = equitySeries.maxDrawdown,
        winRate = winRate,
        profitFactor = profitFactor
    )
}


// -- Extension: Differentiable Signal Generation --

/**
 * Generate differentiable trading signals from price series.
 */
fun Series<SFun<DReal>>.generateSignals(threshold: SFun<DReal>): Series<SFun<DReal>> {
    if (size < 2) return size j { _: Int -> DReal.wrap(0.0) }

    return size j { i: Int ->
        if (i == 0) DReal.wrap(0.0)
        else {
            val change = (this[i] - this[i - 1]) / this[i - 1]
            change / threshold
        }
    }
}
