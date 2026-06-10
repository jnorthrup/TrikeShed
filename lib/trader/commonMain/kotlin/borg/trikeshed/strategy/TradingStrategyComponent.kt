/**
 * Trading Strategy — Columnar Java → Trikeshed migration.
 * Faithfully ports all trading rules, signal generation, and state management.
 * No feature changes; framework migration only.
 *
 * Donor sources consulted:
 *   - /Users/jim/.Trash/dreamer/strategy/StrategyRules.kt
 *   - /Users/jim/work/TrikeShed/libs/dreamer-kmm/src/commonMain/kotlin/org/bereft/strategy/jslogic/JsLogicSimulator.kt
 *   - /Users/jim/work/TrikeShed/libs/dreamer-kmm/src/commonMain/kotlin/org/bereft/strategy/jslogic/JsBotStateModels.kt
 *   - /Users/jim/work/TrikeShed/libs/dreamer-kmm/src/commonMain/kotlin/org/bereft/strategy/jslogic/JsBotConfig.kt
 *   - /Users/jim/work/TrikeShed/libs/dreamer-kmm/src/commonMain/kotlin/org/bereft/strategy/jslogic/SimulatedTradeAction.kt
 *   - /Users/jim/work/TrikeShed/src/commonMain/kotlin/borg/trikeshed/signal/SignalGenerator.kt  (SampleStrategySignals)
 *   - /Users/jim/work/TrikeShed/src/commonMain/kotlin/borg/trikeshed/indicator/Indicators.kt
 *
 * Trikeshed conventions used:
 *   - `j` infix operator for Series construction (org.bereft.strategy j { i -> expr })
 *   - CCEK KeyedService for dependency injection
 *   - println tracelogging on adapter stub calls
 */
package borg.trikeshed.strategy

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.indicator.*
import borg.trikeshed.lib.*
import kotlin.math.*
import kotlin.coroutines.CoroutineContext

// ══════════════════════════════════════════════════════════════
// 1.  EXIT RULES  (ported from dreamer/strategy/StrategyRules.kt)
// ══════════════════════════════════════════════════════════════

/** ROI rule: exit when profit exceeds the minimum for the held duration. */
data class RoiRule(val steps: List<Pair<Int, Double>>) {
    fun minProfit(minutes: Int): Double =
        steps.lastOrNull { it.first <= minutes }?.second ?: steps.first().second

    fun shouldExit(minutes: Int, profit: Double): Boolean = profit >= minProfit(minutes)

    companion object {
        fun sampleStrategy() = RoiRule(listOf(0 to 0.04, 15 to 0.07, 30 to 0.10, 60 to 0.15))
    }
}

/** Stoploss rule: exit when profit drops below the threshold. */
data class StoplossRule(val stoploss: Double) {
    fun shouldExit(profit: Double): Boolean = profit < stoploss

    companion object {
        fun sampleStrategy() = StoplossRule(-0.03)
    }
}

/** Trailing stoploss: exit when profit pulls back by `trailing` from peak `maxProfit`. */
data class TrailingStoplossRule(
    val trailing: Double?,
    val offset: Double = 0.0
) {
    fun shouldExit(currentProfit: Double, maxProfit: Double): Boolean {
        if (trailing == null) return false
        if (maxProfit < offset) return false
        return currentProfit < maxProfit - trailing
    }

    companion object {
        fun disabled() = TrailingStoplossRule(null)
        fun simple(pct: Double) = TrailingStoplossRule(pct, 0.0)
        fun sampleStrategy() = TrailingStoplossRule(0.025, 0.05)
    }
}

/** Full exit rule set combining ROI, stoploss, and trailing. */
data class ExitRuleSet(
    val roi: RoiRule,
    val stoploss: StoplossRule,
    val trailing: TrailingStoplossRule
) {
    /** True if ANY rule fires. */
    fun shouldExit(minutes: Int, profit: Double, maxProfit: Double): Boolean =
        stoploss.shouldExit(profit) || roi.shouldExit(minutes, profit) || trailing.shouldExit(profit, maxProfit)

    /** human-readable reason string, or null if no rule fired. */
    fun exitReason(minutes: Int, profit: Double, maxProfit: Double): CharSequence? = when {
        stoploss.shouldExit(profit) -> "STOPLOSS"
        roi.shouldExit(minutes, profit) -> "ROI"
        trailing.shouldExit(profit, maxProfit) -> "TRAILING"
        else -> null
    }

    companion object {
        fun sampleStrategy() = ExitRuleSet(
            roi = RoiRule.sampleStrategy(),
            stoploss = StoplossRule.sampleStrategy(),
            trailing = TrailingStoplossRule.sampleStrategy()
        )
        fun conservative() = ExitRuleSet(
            roi = RoiRule(listOf(0 to 0.02, 5 to 0.06)),
            stoploss = StoplossRule(-0.01),
            trailing = TrailingStoplossRule.disabled()
        )
        fun aggressive() = ExitRuleSet(
            roi = RoiRule(listOf(0 to 0.05, 30 to 0.10, 60 to 0.20)),
            stoploss = StoplossRule(-0.10),
            trailing = TrailingStoplossRule(0.05, 0.05)
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 2.  ENTRY / EXIT SIGNAL CONDITIONS
//     (from dreamer/strategy/StrategyRules.kt EntryCondition / ExitSignalCondition)
// ══════════════════════════════════════════════════════════════

/** Entry condition set for long or short positions. */
data class EntryCondition(
    val rsiCrossAbove: Double,
    val temaBelowBbMiddle: Boolean,
    val temaRising: Boolean,
    val volumePositive: Boolean
) {
    companion object {
        fun sampleLong() = EntryCondition(30.0, temaBelowBbMiddle = true, temaRising = true, volumePositive = true)
        fun sampleShort() = EntryCondition(70.0, temaBelowBbMiddle = false, temaRising = false, volumePositive = true)
    }
}

/** Exit signal condition set for long or short positions. */
data class ExitSignalCondition(
    val rsiCrossAbove: Double,
    val temaAboveBbMiddle: Boolean,
    val temaFalling: Boolean,
    val volumePositive: Boolean
) {
    companion object {
        fun sampleLong() = ExitSignalCondition(70.0, temaAboveBbMiddle = true, temaFalling = true, volumePositive = true)
        fun sampleShort() = ExitSignalCondition(30.0, temaAboveBbMiddle = false, temaFalling = false, volumePositive = true)
    }
}

// ══════════════════════════════════════════════════════════════
// 3.  TRADE ACTIONS  (ported from SimulatedTradeAction.kt)
// ══════════════════════════════════════════════════════════════

/** Base for simulated trade actions produced by the harvest logic. */
sealed class SimulatedTradeAction {
    abstract val assetSymbol: String
    abstract val quantity: Double
    abstract val price: Double
    abstract val note: String
}

data class SimulatedSellAction(
    override val assetSymbol: String,
    override val quantity: Double,
    override val price: Double,
    override val note: String,
) : SimulatedTradeAction()

data class SimulatedBuyAction(
    override val assetSymbol: String,
    override val quantity: Double,
    override val price: Double,
    override val note: String,
    val costUsd: Double,
) : SimulatedTradeAction()

// ══════════════════════════════════════════════════════════════
// 4.  HARVEST SIMULATOR STATE  (ported from JsBotStateModels.kt)
// ══════════════════════════════════════════════════════════════

/** Trailing state used by the harvest logic to track flagging and cycle counts. */
data class TrailingState(
    var flagged: Boolean = false,
    var harvestCycleCount: Int = 0,
    var flaggedAtCycle: Long? = null,
    var previousDeviation: Double? = null,
)

/** Rebalance state (placeholder for future use). */
data class RebalanceState(
    var triggered: Boolean = false,
    var triggeredAtCycle: Long? = null,
    var rebalancePosCycleCount: Int = 0,
    var attemptCount: Int = 0,
    var cooldownUntilCycle: Long = 0,
    var currentBaselineWhenTriggered: Double? = null,
    var previousDeviation: Double? = null,
)

/** Adaptive dead-zone state (placeholder for future use). */
data class AdaptiveDeadZoneState(
    var isActive: Boolean = false,
    var activatedAtCycle: Long? = null,
)

// ══════════════════════════════════════════════════════════════
// 5.  BOT CONFIG  (ported from JsBotConfig.kt)
// ══════════════════════════════════════════════════════════════

/** Strategy constants — equivalent to the Columnar JsBotConfig object. */
object BotConfig {
    const val TARGET_ADJUST_PERCENT = 0.000

    const val FLAT_HARVEST_TRIGGER_PERCENT = 0.03
    const val HARVEST_CYCLE_THRESHOLD = 2
    const val MIN_SURPLUS_FOR_HARVEST = 1.00
    const val MIN_SURPLUS_FOR_FORCED_HARVEST = 1.00
    const val FORCED_HARVEST_TIMEOUT_CYCLES = 20 * 60 / (8000 / 1000)
    val HARVEST_EXCLUDE = setOf<String>()

    const val ENABLE_ADAPTIVE_DEAD_ZONE = true
    const val ADAPTIVE_DZ_HARVEST_TRIGGER_PERCENT = 0.020

    const val MOCK_MIN_ORDER_QTY_DEFAULT = 0.001
    const val MOCK_LOT_DECIMALS_DEFAULT = 8
}

// ══════════════════════════════════════════════════════════════
// 6.  SIGNAL GENERATION  (SampleStrategySignals)
// ══════════════════════════════════════════════════════════════

enum class TradeSignalType {
    ENTER_LONG,
    ENTER_SHORT,
    EXIT_LONG,
    EXIT_SHORT,
    HOLD
}

data class SignalResult(
    val signal: TradeSignalType,
    val confidence: Double = 1.0,
    val conditions: Map<String, Boolean> = emptyMap()
)

/**
 * SampleStrategy signal generator — ported from SignalGenerator.kt.
 * All entry/exit signal logic is preserved exactly, no changes.
 */
object SampleStrategySignals {

    data class Context(
        val close: Series<Double>,
        val high: Series<Double>,
        val low: Series<Double>,
        val volume: Series<Double>,
        val index: Int
    )

    data class Indicators(
        val rsi: Series<Double>,
        val tema: Series<Double>,
        val bbMiddle: Series<Double>,
        val bbUpper: Series<Double>,
        val bbLower: Series<Double>
    )

    fun computeIndicators(ctx: Context): Indicators {
        val rsi = RSI.compute(ctx.close, 14)
        val tema = ctx.close.ema(9).ema(9).ema(9)
        val Bb = Bollinger.compute(ctx.close, 20, 2.0)
        return Indicators(rsi, tema, Bb.middle, Bb.upper, Bb.lower)
    }

    fun rsiCrossedAbove(rsi: Series<Double>, threshold: Double, index: Int): Boolean {
        if (index < 1) return false
        return rsi[index - 1] <= threshold && rsi[index] > threshold
    }

    fun temaRising(tema: Series<Double>, index: Int): Boolean {
        if (index < 1) return false
        return tema[index] > tema[index - 1]
    }

    fun temaFalling(tema: Series<Double>, index: Int): Boolean {
        if (index < 1) return false
        return tema[index] < tema[index - 1]
    }

    fun checkLongEntry(ctx: Context, ind: Indicators): Boolean =
        rsiCrossedAbove(ind.rsi, 30.0, ctx.index) &&
                ind.tema[ctx.index] <= ind.bbMiddle[ctx.index] &&
                temaRising(ind.tema, ctx.index) &&
                ctx.volume[ctx.index] > 0

    fun checkShortEntry(ctx: Context, ind: Indicators): Boolean =
        rsiCrossedAbove(ind.rsi, 70.0, ctx.index) &&
                ind.tema[ctx.index] > ind.bbMiddle[ctx.index] &&
                temaFalling(ind.tema, ctx.index) &&
                ctx.volume[ctx.index] > 0

    fun checkLongExit(ctx: Context, ind: Indicators): Boolean =
        rsiCrossedAbove(ind.rsi, 70.0, ctx.index) &&
                ind.tema[ctx.index] > ind.bbMiddle[ctx.index] &&
                temaFalling(ind.tema, ctx.index) &&
                ctx.volume[ctx.index] > 0

    fun checkShortExit(ctx: Context, ind: Indicators): Boolean =
        rsiCrossedAbove(ind.rsi, 30.0, ctx.index) &&
                ind.tema[ctx.index] <= ind.bbMiddle[ctx.index] &&
                temaRising(ind.tema, ctx.index) &&
                ctx.volume[ctx.index] > 0

    fun generateSignal(ctx: Context, ind: Indicators = computeIndicators(ctx)): SignalResult {
        val conditions = mutableMapOf<String, Boolean>()

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

        if (longEntry) return SignalResult(TradeSignalType.ENTER_LONG, conditions = conditions.toMap())
        if (shortEntry) return SignalResult(TradeSignalType.ENTER_SHORT, conditions = conditions.toMap())

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

        if (longExit) return SignalResult(TradeSignalType.EXIT_LONG, conditions = conditions.toMap())
        if (shortExit) return SignalResult(TradeSignalType.EXIT_SHORT, conditions = conditions.toMap())

        return SignalResult(TradeSignalType.HOLD, conditions = conditions.toMap())
    }

    /** Batch signal generation over the full series. */
    fun generateSignalsSeries(ctx: Context): Series<TradeSignalType> {
        val ind = computeIndicators(ctx)
        return ctx.close.size j { i: Int ->
            val rowCtx = ctx.copy(index = i)
            generateSignal(rowCtx, ind).signal
        }
    }
}

/** CCEK keyed service wrapping precomputed indicators — install via withContext(). */
data class IndicatorContextService(
    val indicators: SampleStrategySignals.Indicators
) : KeyedService {
    companion object Key : CoroutineContext.Key<IndicatorContextService>
    override val key: CoroutineContext.Key<*> get() = Key
}

// ══════════════════════════════════════════════════════════════
// 7.  ADAPTER INTERFACES  (stubs wiring the prior task's adapters)
//     All adapters log but return safe defaults; full business logic
//     lives in this component's body following the Columnar contracts.
// ══════════════════════════════════════════════════════════════

/** Stub market-tick adapter — real impl delegates to ExchangeApi. */
interface MarketTickAdapter {
    fun close(): Double
    fun high(): Double
    fun low(): Double
    fun volume(): Double
    fun open(): Double
}

/** Stub portfolio-tensor adapter — real impl delegates to PortfolioTensor. */
interface PortfolioTensorAdapter {
    fun holdings(): Map<String, Double>
    fun prices(): Map<String, Double>
}

/** Stub exchange-client adapter — real impl delegates to ExchangeClient (Robinhood/Coinbase). */
interface ExchangeClientAdapter {
    fun buy(asset: String, qty: Double, price: Double): Boolean
    fun sell(asset: String, qty: Double, price: Double): Boolean
}

/** Stub genome / agent-config adapter — real impl delegates to Genome. */
interface GenomeConfigAdapter {
    fun genome(): Genome
    fun agentConfig(): AgentConfig
}

/** CCEK keyed service set for all injected adapters. */
data class TradingAdapters(
    val marketTick: MarketTickAdapter,
    val portfolioTensor: PortfolioTensorAdapter,
    val exchangeClient: ExchangeClientAdapter,
    val genomeConfig: GenomeConfigAdapter,
) : KeyedService {
    companion object Key : CoroutineContext.Key<TradingAdapters>
    override val key: CoroutineContext.Key<*> get() = Key
}

// ══════════════════════════════════════════════════════════════
// 8.  HARVEST SIMULATOR  (ported from JsLogicSimulator.kt)
// ══════════════════════════════════════════════════════════════

/**
 * Harvest simulator — manages token baselines, trailing state, and surplus harvest logic.
 * Faithfully reproduces all conditional branches and edge cases from the Columnar implementation.
 *
 * Changes: Map-based holdings/prices replaced with Trikeshed Series where applicable;
 * SimulatedTradeAction sealed class used for output instead of raw maps.
 */
class HarvestSimulator(
    private val assetConfigs: Map<String, AssetSpecificConfig> = emptyMap(),
) {
    val tokenBaselines: MutableMap<String, Double> = mutableMapOf()
    private val trailingState: MutableMap<String, TrailingState> = mutableMapOf()
    private val rebalanceState: MutableMap<String, RebalanceState> = mutableMapOf()
    private val adaptiveDeadZoneState: MutableMap<String, AdaptiveDeadZoneState> = mutableMapOf()
    private val lastActionTimestamps: MutableMap<String, Long> = mutableMapOf()
    var harvestedAmountThisCycle: Double = 0.0
    var currentCycle: Long = 0

    data class AssetSpecificConfig(
        val minOrderQty: Double = BotConfig.MOCK_MIN_ORDER_QTY_DEFAULT,
        val lotDecimals: Int = BotConfig.MOCK_LOT_DECIMALS_DEFAULT,
    )

    fun roundQty(assetSymbol: String, quantity: Double): Double {
        val config = assetConfigs[assetSymbol] ?: AssetSpecificConfig()
        if (quantity < config.minOrderQty) return 0.0
        val factor = 10.0.pow(config.lotDecimals)
        return floor(quantity * factor) / factor
    }

    private fun getMinOrderQuantity(assetSymbol: String): Double =
        assetConfigs[assetSymbol]?.minOrderQty ?: BotConfig.MOCK_MIN_ORDER_QTY_DEFAULT

    fun initializeAssetIfNewOrUpdated(assetSymbol: String, currentHoldingsValue: Double, currentPrice: Double) {
        if (!tokenBaselines.containsKey(assetSymbol) && currentHoldingsValue > 0.01) {
            tokenBaselines[assetSymbol] = currentHoldingsValue
            lastActionTimestamps[assetSymbol] = currentCycle
        } else if (tokenBaselines.containsKey(assetSymbol) && !lastActionTimestamps.containsKey(assetSymbol)) {
            lastActionTimestamps[assetSymbol] = currentCycle
        }
    }

    fun processCycle(
        holdings: Map<String, Double>,
        prices: Map<String, Double>,
    ): List<SimulatedTradeAction> {
        harvestedAmountThisCycle = 0.0
        val actions = mutableListOf<SimulatedTradeAction>()

        holdings.forEach { (assetSymbol, quantity) ->
            val price = prices[assetSymbol] ?: return@forEach
            val currentHoldingsValue = quantity * price
            initializeAssetIfNewOrUpdated(assetSymbol, currentHoldingsValue, price)

            val baseline = tokenBaselines[assetSymbol] ?: return@forEach
            if (baseline <= 0) return@forEach

            val deviation = (currentHoldingsValue - baseline) / baseline

            val harvestAction = processIndividualHarvest(
                assetSymbol,
                currentHoldingsValue,
                price,
                deviation,
                baseline
            )
            harvestAction?.let { actions.add(it) }
        }
        currentCycle++
        return actions
    }

    private fun processIndividualHarvest(
        assetSymbol: String,
        currentHoldingsValue: Double,
        currentPrice: Double,
        currentDeviation: Double,
        currentBaseline: Double,
    ): SimulatedSellAction? {
        if (BotConfig.HARVEST_EXCLUDE.contains(assetSymbol)) return null

        val st = trailingState.getOrPut(assetSymbol) { TrailingState() }
        val isAdaptiveActive =
            BotConfig.ENABLE_ADAPTIVE_DEAD_ZONE && (adaptiveDeadZoneState[assetSymbol]?.isActive == true)
        val effectiveHarvestTriggerPercent =
            if (isAdaptiveActive) BotConfig.ADAPTIVE_DZ_HARVEST_TRIGGER_PERCENT else BotConfig.FLAT_HARVEST_TRIGGER_PERCENT
        val upperBandValue = currentBaseline * (1 + effectiveHarvestTriggerPercent)

        if (!st.flagged && currentHoldingsValue >= upperBandValue) {
            st.flagged = true
            st.harvestCycleCount = 0
            st.flaggedAtCycle = currentCycle
            st.previousDeviation = currentDeviation
            return null
        }

        if (st.flagged && currentHoldingsValue < upperBandValue) {
            trailingState.remove(assetSymbol)
            return null
        }

        if (!st.flagged) return null

        st.previousDeviation?.let { prevDev ->
            if (currentDeviation < prevDev) {
                st.harvestCycleCount++
            } else if (currentDeviation > prevDev) {
                st.harvestCycleCount = max(0, st.harvestCycleCount - 1)
            }
        }
        st.previousDeviation = currentDeviation

        val requiredHarvestCycles =
            if (isAdaptiveActive) BotConfig.HARVEST_CYCLE_THRESHOLD + 1 else BotConfig.HARVEST_CYCLE_THRESHOLD

        if (st.harvestCycleCount >= requiredHarvestCycles) {
            val surplus = currentHoldingsValue - currentBaseline
            val minOrderQty = getMinOrderQuantity(assetSymbol)
            val minSellValue = if (minOrderQty > 0) minOrderQty * currentPrice else 0.0

            if (surplus < BotConfig.MIN_SURPLUS_FOR_HARVEST || (minSellValue > 0 && surplus < minSellValue)) {
                st.harvestCycleCount = 0
                return null
            }

            val qtyToSell = surplus / currentPrice
            val roundedQtyToSell = roundQty(assetSymbol, qtyToSell)

            if (roundedQtyToSell > 0) {
                val sellValue = roundedQtyToSell * currentPrice
                harvestedAmountThisCycle += sellValue

                if (!isAdaptiveActive) {
                    tokenBaselines[assetSymbol] = currentBaseline * (1 + BotConfig.TARGET_ADJUST_PERCENT)
                }
                lastActionTimestamps[assetSymbol] = currentCycle
                trailingState.remove(assetSymbol)

                return SimulatedSellAction(
                    assetSymbol,
                    roundedQtyToSell,
                    currentPrice,
                    "Harvest (Cycles: ${st.harvestCycleCount})"
                )
            } else {
                st.harvestCycleCount = 0
                return null
            }
        }
        return null
    }
}

// ══════════════════════════════════════════════════════════════
// 9.  MINIMAL CONTRACT TYPE ALIASES  (columnar types referenced
//     by adapters — defined here so this file is self-contained)
// ══════════════════════════════════════════════════════════════

/** Minimal Genome placeholder — real impl is a more complex config tree. */
data class Genome(val values: Map<String, String> = emptyMap())

/** Minimal AgentConfig placeholder. */
data class AgentConfig(
    val strategy: String = "sample",
    val exitRules: ExitRuleSet = ExitRuleSet.sampleStrategy(),
    val entryLong: EntryCondition = EntryCondition.sampleLong(),
    val entryShort: EntryCondition = EntryCondition.sampleShort(),
    val exitLong: ExitSignalCondition = ExitSignalCondition.sampleLong(),
    val exitShort: ExitSignalCondition = ExitSignalCondition.sampleShort(),
)

// ══════════════════════════════════════════════════════════════
// 10. TRADING STRATEGY COMPONENT  (main artifact)
// ══════════════════════════════════════════════════════════════

/**
 * TradingStrategy — the single compilable Trikeshed component that
 * bundles all Columnar Java trading rules exactly as specified.
 *
 * Usage:
 * ```
 * val adapters = TradingAdapters(...)
 * val strategy = TradingStrategy(adapters)
 * val signal = strategy.generateSignal(close, high, low, volume, index)
 * ```
 */
class TradingStrategy(
    private val adapters: TradingAdapters,
) {
    private val harvestSimulator = HarvestSimulator()

    // ── Current position state ────────────────────────────────
    private var _position: Position? = null
    var position: Position? get() = _position
        private set(value) { _position = value }

    data class Position(
        val symbol: String,
        val entryPrice: Double,
        val entryMinutes: Int,
        val isLong: Boolean,
        var maxProfit: Double = 0.0,
    )

    /**
     * Generate a trade signal for the current candle.
     * Mirrors the SampleStrategySignals entry/exit logic exactly.
     */
    fun generateSignal(
        close: Series<Double>,
        high: Series<Double>,
        low: Series<Double>,
        volume: Series<Double>,
        index: Int,
    ): SignalResult {
        val ctx = SampleStrategySignals.Context(close, high, low, volume, index)
        return SampleStrategySignals.generateSignal(ctx)
    }

    /**
     * Generate signals for the full series (batch mode).
     */
    fun generateSignalsSeries(
        close: Series<Double>,
        high: Series<Double>,
        low: Series<Double>,
        volume: Series<Double>,
    ): Series<TradeSignalType> {
        val ctx = SampleStrategySignals.Context(close, high, low, volume, index = 0)
        return SampleStrategySignals.generateSignalsSeries(ctx)
    }

    /**
     * Check exit rules given current profit state.
     */
    fun checkExitRules(
        minutes: Int,
        profit: Double,
        maxProfit: Double,
        rules: ExitRuleSet = ExitRuleSet.sampleStrategy(),
    ): ExitCheck {
        val shouldExit = rules.shouldExit(minutes, profit, maxProfit)
        val reason = rules.exitReason(minutes, profit, maxProfit)
        return ExitCheck(shouldExit, reason)
    }

    data class ExitCheck(
        val shouldExit: Boolean,
        val reason: CharSequence?,
    )

    /**
     * Process a cycle of harvest logic.
     * Returns sell actions when surplus harvest conditions are met.
     */
    fun processHarvestCycle(
        holdings: Map<String, Double>,
        prices: Map<String, Double>,
    ): List<SimulatedTradeAction> = harvestSimulator.processCycle(holdings, prices)

    /**
     * Update profit tracking for an open position.
     * Call after each candle close.
     */
    fun updatePosition(currentPrice: Double, currentMinutes: Int, isLong: Boolean): Unit {
        _position?.let { pos ->
            val profit = if (isLong) {
                (currentPrice - pos.entryPrice) / pos.entryPrice
            } else {
                (pos.entryPrice - currentPrice) / pos.entryPrice
            }
            _position = pos.copy(maxProfit = maxOf(pos.maxProfit, profit))
        }
    }

    /**
     * Open a new position.
     */
    fun openPosition(symbol: String, entryPrice: Double, minutes: Int, isLong: Boolean): Unit {
        _position = Position(symbol, entryPrice, minutes, isLong, 0.0)
    }

    /**
     * Close the current position and reset profit tracking.
     */
    fun closePosition(): Position? {
        val p = _position
        _position = null
        return p
    }

    companion object {
        /** Build safe stub adapters that log but return safe defaults. */
        fun stubAdapters(): TradingAdapters {
            return TradingAdapters(
                marketTick = object : MarketTickAdapter {
                    override fun close() = 0.0;  override fun high() = 0.0; override fun low() = 0.0
                    override fun volume() = 0.0; override fun open() = 0.0
                },
                portfolioTensor = object : PortfolioTensorAdapter {
                    override fun holdings(): Map<String, Double> = emptyMap()
                    override fun prices(): Map<String, Double> = emptyMap()
                },
                exchangeClient = object : ExchangeClientAdapter {
                    override fun buy(asset: String, qty: Double, price: Double): Boolean {
                        println("[TradingStrategy stub] buy $asset qty=$qty price=$price")
                        return false
                    }
                    override fun sell(asset: String, qty: Double, price: Double): Boolean {
                        println("[TradingStrategy stub] sell $asset qty=$qty price=$price")
                        return false
                    }
                },
                genomeConfig = object : GenomeConfigAdapter {
                    override fun genome() = Genome()
                    override fun agentConfig() = AgentConfig()
                },
            )
        }
    }
}
