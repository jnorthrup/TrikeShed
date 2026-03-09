/**
 * Signal Generator — DSEL for entry/exit signal generation.
 *
 * Extracted from freqtrade's SampleStrategy for use in TrikeShed/moneyfan.
 * Provides composable signal conditions based on technical indicators.
 */
package borg.trikeshed.signal

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.indicator.*
import borg.trikeshed.lib.*
import kotlin.coroutines.CoroutineContext

/**
 * Trade signal enumeration.
 */
enum class SignalType {
    ENTER_LONG,
    ENTER_SHORT,
    EXIT_LONG,
    EXIT_SHORT,
    HOLD
}

/**
 * Signal result with metadata.
 */
data class SignalResult(
    val signal: SignalType,
    val confidence: Double = 1.0,
    val conditions: Map<String, Boolean> = emptyMap()
)

/**
 * SampleStrategy signal generator.
 *
 * Entry conditions (long):
 * - RSI crosses above 30
 * - TEMA <= BB middle (guard)
 * - TEMA is rising (guard)
 * - Volume > 0
 *
 * Entry conditions (short):
 * - RSI crosses above 70
 * - TEMA > BB middle (guard)
 * - TEMA is falling (guard)
 * - Volume > 0
 *
 * Exit conditions (long):
 * - RSI crosses above 70
 * - TEMA > BB middle (guard)
 * - TEMA is falling (guard)
 * - Volume > 0
 *
 * Exit conditions (short):
 * - RSI crosses above 30
 * - TEMA <= BB middle (guard)
 * - TEMA is rising (guard)
 * - Volume > 0
 */
object SampleStrategySignals {

    data class Context(
        val close: Series<Double>,
        val high: Series<Double>,
        val low: Series<Double>,
        val volume: Series<Double>,
        val index: Int  // Current candle index
    )

    /**
     * Precomputed indicators for efficiency.
     */
    data class Indicators(
        val rsi: Series<Double>,
        val tema: Series<Double>,
        val bbMiddle: Series<Double>,
        val bbUpper: Series<Double>,
        val bbLower: Series<Double>
    )

    /**
     * Compute indicators needed for signal generation.
     */
    fun computeIndicators(ctx: Context): Indicators {
        val rsi = RSI.compute(ctx.close, 14)
        val tema = ctx.close.ema(9).ema(9).ema(9)  // TEMA = 3*EMA1 - 3*EMA2 + EMA3
        val bb = Bollinger.compute(ctx.close, 20, 2.0)
        return Indicators(rsi, tema, bb.middle, bb.upper, bb.lower)
    }

    /**
     * Check if RSI crossed above threshold at current index.
     */
    fun rsiCrossedAbove(rsi: Series<Double>, threshold: Double, index: Int): Boolean {
        if (index < 1) return false
        return rsi[index - 1] <= threshold && rsi[index] > threshold
    }

    /**
     * Check if TEMA is rising (current > previous).
     */
    fun temaRising(tema: Series<Double>, index: Int): Boolean {
        if (index < 1) return false
        return tema[index] > tema[index - 1]
    }

    /**
     * Check if TEMA is falling (current < previous).
     */
    fun temaFalling(tema: Series<Double>, index: Int): Boolean {
        if (index < 1) return false
        return tema[index] < tema[index - 1]
    }

    /**
     * Evaluate long entry conditions.
     */
    fun checkLongEntry(ctx: Context, ind: Indicators): Boolean {
        return rsiCrossedAbove(ind.rsi, 30.0, ctx.index) &&
                ind.tema[ctx.index] <= ind.bbMiddle[ctx.index] &&
                temaRising(ind.tema, ctx.index) &&
                ctx.volume[ctx.index] > 0
    }

    /**
     * Evaluate short entry conditions.
     */
    fun checkShortEntry(ctx: Context, ind: Indicators): Boolean {
        return rsiCrossedAbove(ind.rsi, 70.0, ctx.index) &&
                ind.tema[ctx.index] > ind.bbMiddle[ctx.index] &&
                temaFalling(ind.tema, ctx.index) &&
                ctx.volume[ctx.index] > 0
    }

    /**
     * Evaluate long exit conditions.
     */
    fun checkLongExit(ctx: Context, ind: Indicators): Boolean {
        return rsiCrossedAbove(ind.rsi, 70.0, ctx.index) &&
                ind.tema[ctx.index] > ind.bbMiddle[ctx.index] &&
                temaFalling(ind.tema, ctx.index) &&
                ctx.volume[ctx.index] > 0
    }

    /**
     * Evaluate short exit conditions.
     */
    fun checkShortExit(ctx: Context, ind: Indicators): Boolean {
        return rsiCrossedAbove(ind.rsi, 30.0, ctx.index) &&
                ind.tema[ctx.index] <= ind.bbMiddle[ctx.index] &&
                temaRising(ind.tema, ctx.index) &&
                ctx.volume[ctx.index] > 0
    }

    /**
     * Generate signal for current candle.
     */
    fun generateSignal(ctx: Context, ind: Indicators = computeIndicators(ctx)): SignalResult {
        val conditions = mutableMapOf<String, Boolean>()

        // Check entry signals first
        val longEntry = checkLongEntry(ctx, ind)
        val shortEntry = checkShortEntry(ctx, ind)

        conditions["long_entry_rsi"] = rsiCrossedAbove(ind.rsi, 30.0, ctx.index)
        conditions["long_entry_tema_bb"] = ind.tema[ctx.index] <= ind.bbMiddle[ctx.index]
        conditions["long_entry_tema_rising"] = temaRising(ind.tema, ctx.index)
        conditions["long_entry_volume"] = ctx.volume[ctx.index] > 0

        conditions["short_entry_rsi"] = rsiCrossedAbove(ind.rsi, 70.0, ctx.index)
        conditions["short_entry_tema_bb"] = ind.tema[ctx.index] > ind.bbMiddle[ctx.index]
        conditions["short_entry_tema_falling"] = temaFalling(ind.tema, ctx.index)
        conditions["short_entry_volume"] = ctx.volume[ctx.index] > 0

        if (longEntry) {
            return SignalResult(SignalType.ENTER_LONG, conditions = conditions.toMap())
        }
        if (shortEntry) {
            return SignalResult(SignalType.ENTER_SHORT, conditions = conditions.toMap())
        }

        // Check exit signals
        val longExit = checkLongExit(ctx, ind)
        val shortExit = checkShortExit(ctx, ind)

        conditions["long_exit_rsi"] = rsiCrossedAbove(ind.rsi, 70.0, ctx.index)
        conditions["long_exit_tema_bb"] = ind.tema[ctx.index] > ind.bbMiddle[ctx.index]
        conditions["long_exit_tema_falling"] = temaFalling(ind.tema, ctx.index)
        conditions["long_exit_volume"] = ctx.volume[ctx.index] > 0

        conditions["short_exit_rsi"] = rsiCrossedAbove(ind.rsi, 30.0, ctx.index)
        conditions["short_exit_tema_bb"] = ind.tema[ctx.index] <= ind.bbMiddle[ctx.index]
        conditions["short_exit_tema_rising"] = temaRising(ind.tema, ctx.index)
        conditions["short_exit_volume"] = ctx.volume[ctx.index] > 0

        if (longExit) {
            return SignalResult(SignalType.EXIT_LONG, conditions = conditions.toMap())
        }
        if (shortExit) {
            return SignalResult(SignalType.EXIT_SHORT, conditions = conditions.toMap())
        }

        return SignalResult(SignalType.HOLD, conditions = conditions.toMap())
    }

    /**
     * Generate signals for entire series (batch mode).
     * Returns a Series of signals.
     */
    fun generateSignalsSeries(ctx: Context): Series<SignalType> {
        val ind = computeIndicators(ctx)
        return ctx.close.size j { i: Int ->
            val rowCtx = ctx.copy(index = i)
            generateSignal(rowCtx, ind).signal
        }
    }
}

/**
 * Configurable strategy signal generator.
 * Allows customization of thresholds and conditions.
 */
data class ConfigurableStrategy(
    val longEntryRsi: Double = 30.0,
    val shortEntryRsi: Double = 70.0,
    val longExitRsi: Double = 70.0,
    val shortExitRsi: Double = 30.0,
    val useTemaBbGuard: Boolean = true,
    val useTemaTrendGuard: Boolean = true,
    val requireVolume: Boolean = true
) {

    fun checkLongEntry(ctx: SampleStrategySignals.Context, ind: SampleStrategySignals.Indicators): Boolean {
        val rsiCond = SampleStrategySignals.rsiCrossedAbove(ind.rsi, longEntryRsi, ctx.index)
        val temaBbCond = !useTemaBbGuard || ind.tema[ctx.index] <= ind.bbMiddle[ctx.index]
        val trendCond = !useTemaTrendGuard || SampleStrategySignals.temaRising(ind.tema, ctx.index)
        val volCond = !requireVolume || ctx.volume[ctx.index] > 0
        return rsiCond && temaBbCond && trendCond && volCond
    }

    fun checkShortEntry(ctx: SampleStrategySignals.Context, ind: SampleStrategySignals.Indicators): Boolean {
        val rsiCond = SampleStrategySignals.rsiCrossedAbove(ind.rsi, shortEntryRsi, ctx.index)
        val temaBbCond = !useTemaBbGuard || ind.tema[ctx.index] > ind.bbMiddle[ctx.index]
        val trendCond = !useTemaTrendGuard || SampleStrategySignals.temaFalling(ind.tema, ctx.index)
        val volCond = !requireVolume || ctx.volume[ctx.index] > 0
        return rsiCond && temaBbCond && trendCond && volCond
    }

    fun checkLongExit(ctx: SampleStrategySignals.Context, ind: SampleStrategySignals.Indicators): Boolean {
        val rsiCond = SampleStrategySignals.rsiCrossedAbove(ind.rsi, longExitRsi, ctx.index)
        val temaBbCond = !useTemaBbGuard || ind.tema[ctx.index] > ind.bbMiddle[ctx.index]
        val trendCond = !useTemaTrendGuard || SampleStrategySignals.temaFalling(ind.tema, ctx.index)
        val volCond = !requireVolume || ctx.volume[ctx.index] > 0
        return rsiCond && temaBbCond && trendCond && volCond
    }

    fun checkShortExit(ctx: SampleStrategySignals.Context, ind: SampleStrategySignals.Indicators): Boolean {
        val rsiCond = SampleStrategySignals.rsiCrossedAbove(ind.rsi, shortExitRsi, ctx.index)
        val temaBbCond = !useTemaBbGuard || ind.tema[ctx.index] <= ind.bbMiddle[ctx.index]
        val trendCond = !useTemaTrendGuard || SampleStrategySignals.temaRising(ind.tema, ctx.index)
        val volCond = !requireVolume || ctx.volume[ctx.index] > 0
        return rsiCond && temaBbCond && trendCond && volCond
    }

    fun generateSignal(ctx: SampleStrategySignals.Context): SignalResult {
        val ind = SampleStrategySignals.computeIndicators(ctx)

        if (checkLongEntry(ctx, ind)) return SignalResult(SignalType.ENTER_LONG)
        if (checkShortEntry(ctx, ind)) return SignalResult(SignalType.ENTER_SHORT)
        if (checkLongExit(ctx, ind)) return SignalResult(SignalType.EXIT_LONG)
        if (checkShortExit(ctx, ind)) return SignalResult(SignalType.EXIT_SHORT)

        return SignalResult(SignalType.HOLD)
    }
}

/**
 * CCEK keyed service wrapping precomputed SampleStrategy indicators.
 * Install via withContext(IndicatorContextService(indicators)) so all coroutines
 * in the scope share one indicator computation instead of recomputing per-candle.
 */
data class IndicatorContextService(
    val indicators: SampleStrategySignals.Indicators
) : KeyedService {
    companion object Key : CoroutineContext.Key<IndicatorContextService>
    override val key: CoroutineContext.Key<*> get() = Key
}
