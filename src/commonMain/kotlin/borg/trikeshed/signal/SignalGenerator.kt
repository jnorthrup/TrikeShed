/**
 * Signal Generator — Entry and exit signal generation from technical indicators.
 *
 * This module ports the signal logic from freqtrade's SampleStrategy into
 * a modular, testable TrikeShed implementation using Series-based indicators.
 *
 * Signals are generated from combinations of technical indicators:
 * - RSI oversold/overbought conditions
 * - Bollinger Band breakouts
 * - MACD crossovers
 * - Stochastic signals
 * - ADX trend strength
 */
package borg.trikeshed.signal

import borg.trikeshed.indicator.*
import borg.trikeshed.lib.*

/**
 * Represents a trading signal with metadata.
 */
data class Signal(
    val action: Action,
    val strength: Double = 1.0,
    val indicators: Map<String, Double> = emptyMap(),
    val reason: String? = null
) {
    enum class Action { BUY, SELL, NONE }

    companion object {
        val NONE = Signal(Action.NONE)
        fun buy(strength: Double = 1.0, reason: String? = null, vararg indicators: Pair<String, Double>): Signal =
            Signal(Action.BUY, strength, indicators.toMap(), reason)
        fun sell(strength: Double = 1.0, reason: String? = null, vararg indicators: Pair<String, Double>): Signal =
            Signal(Action.SELL, strength, indicators.toMap(), reason)
    }
}

/**
 * Signal configuration for customizing entry/exit thresholds.
 */
data class SignalConfig(
    // RSI thresholds
    val rsiBuyThreshold: Double = 30.0,
    val rsiSellThreshold: Double = 70.0,

    // Bollinger Band settings
    val bollingerDeviation: Double = 2.0,
    val bollingerBuyBelowLower: Boolean = true,
    val bollingerSellAboveUpper: Boolean = true,

    // MACD settings
    val macdCrossOverBuy: Boolean = true,
    val macdCrossUnderSell: Boolean = true,

    // Stochastic settings
    val stochKPeriod: Int = 14,
    val stochDPeriod: Int = 3,
    val stochBuyBelow: Double = 20.0,
    val stochSellAbove: Double = 80.0,

    // ADX settings
    val adxPeriod: Int = 14,
    val adxMinTrend: Double = 25.0,

    // General
    val requireVolumeConfirmation: Boolean = false,
    val minADXForTrend: Double = 20.0
) {
    companion object {
        /** Conservative: stricter thresholds, require trend confirmation */
        fun conservative(): SignalConfig = SignalConfig(
            rsiBuyThreshold = 25.0,
            rsiSellThreshold = 75.0,
            bollingerDeviation = 2.5,
            adxMinTrend = 30.0,
            requireVolumeConfirmation = true
        )

        /** Aggressive: looser thresholds, more signals */
        fun aggressive(): SignalConfig = SignalConfig(
            rsiBuyThreshold = 35.0,
            rsiSellThreshold = 65.0,
            bollingerDeviation = 1.5,
            adxMinTrend = 20.0
        )

        /** Balanced: default SampleStrategy-like settings */
        fun balanced(): SignalConfig = SignalConfig()
    }
}

/**
 * Signal Generator — produces buy/sell signals from OHLCV data.
 *
 * Ported from freqtrade's SampleStrategy signal logic.
 */
class SignalGenerator(private val config: SignalConfig = SignalConfig.balanced()) {

    /**
     * Generate signals from price series.
     * @param close Close price series
     * @param high High price series (optional, for some indicators)
     * @param low Low price series (optional, for some indicators)
     * @param volume Volume series (optional, for volume confirmation)
     * @return Series of signals for each time point
     */
    fun generate(
        close: Series<Double>,
        high: Series<Double>? = null,
        low: Series<Double>? = null,
        volume: Series<Double>? = null
    ): Series<Signal> {
        val h = high ?: close
        val l = low ?: close
        val v = volume ?: close.size j { _: Int -> 1.0 }

        // Pre-compute all indicators
        val rsi = RSI.compute(close)
        val bollinger = Bollinger.compute(close, 20, config.bollingerDeviation)
        val macd = EmaMacd.compute(close)
        val stoch = Stochastic.compute(h, l, close, config.stochKPeriod, config.stochDPeriod)
        val adx = ADX.compute(h, l, close, config.adxPeriod)

        return close.size j { i: Int ->
            generateSignal(i, close, h, l, v, rsi, bollinger, macd, stoch, adx)
        }
    }

    /**
     * Generate a single signal at index i.
     */
    private fun generateSignal(
        i: Int,
        close: Series<Double>,
        high: Series<Double>,
        low: Series<Double>,
        volume: Series<Double>,
        rsi: Series<Double>,
        bollinger: Bollinger.Result,
        macd: Map<String, Series<Double>>,
        stoch: Stochastic.Result,
        adx: ADX.Result
    ): Signal {
        if (i < 30) return Signal.NONE // Need enough history for indicators

        val buySignals = mutableListOf<String>()
        val sellSignals = mutableListOf<String>()

        // RSI signal
        if (rsi[i] < config.rsiBuyThreshold) {
            buySignals.add("RSI oversold (${rsi[i]})")
        } else if (rsi[i] > config.rsiSellThreshold) {
            sellSignals.add("RSI overbought (${rsi[i]})")
        }

        // Bollinger Band signal
        if (config.bollingerBuyBelowLower && close[i] < bollinger.lower[i]) {
            buySignals.add("Price below lower BB")
        } else if (config.bollingerSellAboveUpper && close[i] > bollinger.upper[i]) {
            sellSignals.add("Price above upper BB")
        }

        // MACD crossover signal
        val macdLine = macd["macd_line"]!!
        val macdSignalLine = macd["macd_signal"]!!
        if (config.macdCrossOverBuy && i > 0) {
            if (macdLine[i - 1] <= macdSignalLine[i - 1] && macdLine[i] > macdSignalLine[i]) {
                buySignals.add("MACD bullish crossover")
            }
        }
        if (config.macdCrossUnderSell && i > 0) {
            if (macdLine[i - 1] >= macdSignalLine[i - 1] && macdLine[i] < macdSignalLine[i]) {
                sellSignals.add("MACD bearish crossover")
            }
        }

        // Stochastic signal
        if (stoch.k[i] < config.stochBuyBelow && stoch.d[i] < config.stochBuyBelow) {
            buySignals.add("Stochastic oversold (K=${stoch.k[i]}, D=${stoch.d[i]})")
        } else if (stoch.k[i] > config.stochSellAbove && stoch.d[i] > config.stochSellAbove) {
            sellSignals.add("Stochastic overbought (K=${stoch.k[i]}, D=${stoch.d[i]})")
        }

        // ADX trend strength filter
        val hasTrend = adx.adx[i] >= config.adxMinTrend

        // Volume confirmation (if required)
        val volumeOk = if (config.requireVolumeConfirmation) {
            var sum = 0.0
            for (j in 0 until 20) {
                sum += volume[maxOf(0, i - j)]
            }
            val avgVolume = sum / 20.0
            volume[i] > avgVolume
        } else true

        // Determine final signal
        val signal = when {
            buySignals.isNotEmpty() && (hasTrend || !config.requireVolumeConfirmation) && volumeOk -> {
                Signal.buy(
                    strength = calculateStrength(buySignals.size, sellSignals.size),
                    reason = buySignals.joinToString("; "),
                    "RSI" to rsi[i],
                    "ADX" to adx.adx[i],
                    "StochK" to stoch.k[i]
                )
            }
            sellSignals.isNotEmpty() && volumeOk -> {
                Signal.sell(
                    strength = calculateStrength(sellSignals.size, buySignals.size),
                    reason = sellSignals.joinToString("; "),
                    "RSI" to rsi[i],
                    "ADX" to adx.adx[i],
                    "StochK" to stoch.k[i]
                )
            }
            else -> Signal.NONE
        }
        return signal
    }

    /**
     * Calculate signal strength based on number of confirming indicators.
     */
    private fun calculateStrength(confirming: Int, opposing: Int): Double {
        val net = confirming - opposing
        return minOf(1.0, net / 3.0) // Normalize to [0, 1]
    }
}

/**
 * Sample Strategy — direct port of freqtrade's SampleStrategy logic.
 *
 * This is the canonical implementation that matches the Python original.
 */
object SampleStrategy {
    private val generator = SignalGenerator(SignalConfig.balanced())

    /**
     * Generate entry/exit signals matching freqtrade's SampleStrategy.
     */
    fun generateSignals(
        close: Series<Double>,
        high: Series<Double> = close,
        low: Series<Double> = close,
        volume: Series<Double> = close.size j { _: Int -> 1.0 }
    ): Series<Signal> {
        return generator.generate(close, high, low, volume)
    }

    /**
     * Check for long entry condition at index i.
     * Matches freqtrade's `enter_long_conditions` logic.
     */
    fun shouldEnterLong(
        i: Int,
        close: Series<Double>,
        high: Series<Double>,
        low: Series<Double>,
        volume: Series<Double>
    ): Boolean {
        val signals = generateSignals(close, high, low, volume)
        return signals[i].action == Signal.Action.BUY
    }

    /**
     * Check for exit condition at index i.
     * Matches freqtrade's `exit_trade` logic (signal-based, not ROI/stoploss).
     */
    fun shouldExit(
        i: Int,
        close: Series<Double>,
        high: Series<Double>,
        low: Series<Double>,
        volume: Series<Double>
    ): Boolean {
        val signals = generateSignals(close, high, low, volume)
        return signals[i].action == Signal.Action.SELL
    }
}

/**
 * Convenience extension function to generate signals from a close series.
 */
fun Series<Double>.generateSignals(config: SignalConfig = SignalConfig.balanced()): Series<Signal> {
    val generator = SignalGenerator(config)
    return generator.generate(this)
}

/**
 * Convenience extension to get buy signals only.
 */
fun Series<Signal>.buySignals(): Series<Signal> =
    this.size j { i: Int ->
        val signal = this[i]
        if (signal.action == Signal.Action.BUY) signal else Signal.NONE
    }

/**
 * Convenience extension to get sell signals only.
 */
fun Series<Signal>.sellSignals(): Series<Signal> =
    this.size j { i: Int ->
        val signal = this[i]
        if (signal.action == Signal.Action.SELL) signal else Signal.NONE
    }

/**
 * Count signals in a series.
 */
fun Series<Signal>.countSignals(action: Signal.Action): Int =
    (0 until this.size).count { i -> this[i].action == action }

val Series<Signal>.buyCount: Int get() = countSignals(Signal.Action.BUY)
val Series<Signal>.sellCount: Int get() = countSignals(Signal.Action.SELL)
