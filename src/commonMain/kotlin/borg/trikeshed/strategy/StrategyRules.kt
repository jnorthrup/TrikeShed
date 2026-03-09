/**
 * Strategy Rules DSEL — ROI and Stoploss contracts for trading strategies.
 *
 * This module extracts the ROI/Stoploss logic from freqtrade's SampleStrategy
 * into reusable, composable DSEL (Domain-Specific Embedded Language) contracts.
 *
 * Each rule is a pure function that evaluates market conditions and returns
 * a decision signal. Rules can be combined using logical operators.
 */
package borg.trikeshed.strategy

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size

/**
 * Represents a trading decision with optional metadata.
 */
data class Decision(
    val action: Action,
    val reason: String? = null,
    val strength: Double = 1.0
) {
    enum class Action { BUY, SELL, HOLD }
}

/**
 * ROI (Return on Investment) Rule configuration.
 *
 * Defines profit thresholds at different time intervals.
 * Similar to freqtrade's ROI table: { minutes -> profit_ratio }
 */
data class RoiRule(
    val thresholds: Map<Int, Double>,
    val description: String = "Custom ROI"
) {
    companion object {
        /** Default conservative ROI: 2% at 0min, 1% at 10min, 0.5% at 30min, 0% at 60min */
        fun conservative(): RoiRule = RoiRule(
            mapOf(0 to 0.02, 10 to 0.01, 30 to 0.005, 60 to 0.0),
            "Conservative ROI"
        )

        /** Aggressive ROI: 5% at 0min, 3% at 5min, 1% at 20min */
        fun aggressive(): RoiRule = RoiRule(
            mapOf(0 to 0.05, 5 to 0.03, 20 to 0.01),
            "Aggressive ROI"
        )

        /** Quick scalp: 1% at 0min, 0.5% at 2min, 0% at 10min */
        fun scalp(): RoiRule = RoiRule(
            mapOf(0 to 0.01, 2 to 0.005, 10 to 0.0),
            "Scalp ROI"
        )
    }

    /**
     * Evaluate if current profit meets ROI threshold for elapsed minutes.
     * @param profitRatio Current profit as ratio (e.g., 0.02 = 2% profit)
     * @param elapsedMinutes Minutes since entry
     * @return true if profit exceeds threshold for this time period
     */
    fun shouldExit(profitRatio: Double, elapsedMinutes: Int): Boolean {
        val threshold = getThreshold(elapsedMinutes)
        return profitRatio >= threshold
    }

    /**
     * Get the ROI threshold for a given time period.
     * Uses the most specific (closest) threshold that applies.
     */
    private fun getThreshold(elapsedMinutes: Int): Double {
        return thresholds
            .filterKeys { it <= elapsedMinutes }
            .minByOrNull { it.key }
            ?.value
            ?: thresholds.maxByOrNull { it.key }?.value
            ?: 0.0
    }
}

/**
 * Stoploss Rule configuration.
 *
 * Defines loss thresholds and trailing stop behavior.
 */
data class StoplossRule(
    val stoploss: Double,
    val trailing: Boolean = false,
    val trailingOffset: Double = 0.0,
    val description: String = "Custom Stoploss"
) {
    companion object {
        /** Tight stop: -2% fixed */
        fun tight(): StoplossRule = StoplossRule(-0.02, description = "Tight Stop (-2%)")

        /** Standard stop: -5% fixed */
        fun standard(): StoplossRule = StoplossRule(-0.05, description = "Standard Stop (-5%)")

        /** Wide stop: -10% fixed */
        fun wide(): StoplossRule = StoplossRule(-0.10, description = "Wide Stop (-10%)")

        /** Trailing stop: -3% with 1% offset */
        fun trailing(offset: Double = 0.01): StoplossRule =
            StoplossRule(-0.03, trailing = true, trailingOffset = offset, description = "Trailing Stop (-3% + offset)")
    }

    /**
     * Evaluate if current loss triggers stoploss.
     * @param profitRatio Current profit as ratio (negative = loss)
     * @param maxProfitRatio Maximum profit ratio seen (for trailing stops)
     * @return true if stoploss condition is met
     */
    fun shouldExit(profitRatio: Double, maxProfitRatio: Double = 0.0): Boolean {
        return if (trailing) {
            val trailingStop = maxProfitRatio - trailingOffset
            profitRatio <= trailingStop && trailingStop < stoploss
        } else {
            profitRatio <= stoploss
        }
    }
}

/**
 * Time-based exit rule.
 * Exit after a maximum holding period regardless of profit/loss.
 */
data class TimeExitRule(
    val maxMinutes: Int,
    val description: String = "Time exit after $maxMinutes minutes"
) {
    companion object {
        fun dayTrade(): TimeExitRule = TimeExitRule(480, "Day Trade (8h)")
        fun swing(): TimeExitRule = TimeExitRule(10080, "Swing (7d)")
        fun scalp(): TimeExitRule = TimeExitRule(30, "Scalp (30m)")
    }

    fun shouldExit(elapsedMinutes: Int): Boolean = elapsedMinutes >= maxMinutes
}

/**
 * Composite strategy rules combining ROI, Stoploss, and Time exits.
 */
data class StrategyRules(
    val roi: RoiRule? = null,
    val stoploss: StoplossRule? = null,
    val timeExit: TimeExitRule? = null,
    val description: String = "Custom Strategy"
) {
    companion object {
        /** Balanced strategy: moderate ROI, standard stoploss, day trade time limit */
        fun balanced(): StrategyRules = StrategyRules(
            roi = RoiRule.conservative(),
            stoploss = StoplossRule.standard(),
            timeExit = TimeExitRule.dayTrade(),
            description = "Balanced Strategy"
        )

        /** Aggressive strategy: high ROI targets, tight stops */
        fun aggressive(): StrategyRules = StrategyRules(
            roi = RoiRule.aggressive(),
            stoploss = StoplossRule.tight(),
            timeExit = TimeExitRule.swing(),
            description = "Aggressive Strategy"
        )

        /** Scalp strategy: quick profits, very tight stops */
        fun scalp(): StrategyRules = StrategyRules(
            roi = RoiRule.scalp(),
            stoploss = StoplossRule.trailing(0.005),
            timeExit = TimeExitRule.scalp(),
            description = "Scalp Strategy"
        )
    }

    /**
     * Evaluate all rules and return exit decision if any rule triggers.
     * @param profitRatio Current profit ratio
     * @param elapsedMinutes Minutes since entry
     * @param maxProfitRatio Maximum profit ratio seen (for trailing stops)
     * @return Decision with SELL action if any exit condition is met, HOLD otherwise
     */
    fun evaluate(
        profitRatio: Double,
        elapsedMinutes: Int,
        maxProfitRatio: Double = profitRatio
    ): Decision {
        // Check stoploss first (risk management priority)
        if (stoploss != null && stoploss.shouldExit(profitRatio, maxProfitRatio)) {
            return Decision(
                Decision.Action.SELL,
                reason = "Stoploss: ${stoploss.description}",
                strength = 1.0
            )
        }

        // Check time exit
        if (timeExit != null && timeExit.shouldExit(elapsedMinutes)) {
            return Decision(
                Decision.Action.SELL,
                reason = "Time exit: ${timeExit.description}",
                strength = 0.8
            )
        }

        // Check ROI
        if (roi != null && roi.shouldExit(profitRatio, elapsedMinutes)) {
            return Decision(
                Decision.Action.SELL,
                reason = "ROI: ${roi.description}",
                strength = 0.9
            )
        }

        return Decision(Decision.Action.HOLD, reason = null, strength = 0.0)
    }
}

/**
 * Infix DSEL builder for creating strategy rules fluently.
 */
class StrategyBuilder {
    private var roi: RoiRule? = null
    private var stoploss: StoplossRule? = null
    private var timeExit: TimeExitRule? = null
    private var description: String = "Custom Strategy"

    fun withRoi(rule: RoiRule) {
        roi = rule
    }

    fun withStoploss(rule: StoplossRule) {
        stoploss = rule
    }

    fun withTimeExit(rule: TimeExitRule) {
        timeExit = rule
    }

    fun describedAs(desc: String) {
        description = desc
    }

    fun build(): StrategyRules = StrategyRules(roi, stoploss, timeExit, description)
}

/**
 * DSEL function to create strategy rules with fluent syntax.
 */
fun strategy(block: StrategyBuilder.() -> Unit): StrategyRules {
    val builder = StrategyBuilder()
    builder.block()
    return builder.build()
}

/**
 * Infix operators for combining rules.
 */
infix fun RoiRule.and(rule: StoplossRule): Pair<RoiRule, StoplossRule> = this to rule

infix fun Pair<RoiRule, StoplossRule>.and(rule: TimeExitRule): Triple<RoiRule, StoplossRule, TimeExitRule> =
    Triple(this.first, this.second, rule)

/**
 * Convert a Triple to StrategyRules.
 */
fun Triple<RoiRule, StoplossRule, TimeExitRule>.toStrategy(): StrategyRules =
    StrategyRules(first, second, third, "Composite Strategy")
