/**
 * Strategy Rules — DSEL contracts for ROI, Stoploss, and Trailing Stop logic.
 *
 * Extracted from freqtrade's SampleStrategy for use in TrikeShed/moneyfan.
 * These are immutable, composable rule definitions that can be evaluated
 * against trade state (current profit, elapsed time, open price, etc.).
 */
package borg.trikeshed.strategy

import borg.trikeshed.lib.*

/**
 * ROI (Return on Investment) rule definition.
 * Maps elapsed minutes to minimum profit threshold for exit.
 *
 * SampleStrategy ROI:
 * - 0 min:  4%  (immediate 4% gain triggers exit)
 * - 15 min: 7%  (after 15 mins, need 7% to exit)
 * - 30 min: 10% (after 30 mins, need 10% to exit)
 * - 60 min: 15% (after 60 mins, need 15% to exit)
 */
data class RoiRule(
    val thresholds: List<Pair<Int, Double>>  // (minutes, minProfitPercent)
) {
    companion object {
        /** SampleStrategy default ROI configuration */
        fun sampleStrategy(): RoiRule = RoiRule(
            listOf(
                0 to 0.04,    // 4% immediate
                15 to 0.07,   // 7% after 15 mins
                30 to 0.10,   // 10% after 30 mins
                60 to 0.15    // 15% after 60 mins
            )
        )

        /** Conservative ROI: lower targets, faster */
        fun conservative(): RoiRule = RoiRule(
            listOf(
                0 to 0.02,
                10 to 0.04,
                30 to 0.06
            )
        )

        /** Aggressive ROI: higher targets, let winners run */
        fun aggressive(): RoiRule = RoiRule(
            listOf(
                0 to 0.05,
                30 to 0.10,
                60 to 0.20,
                120 to 0.30
            )
        )
    }

    /**
     * Get minimum ROI threshold for elapsed minutes.
     * Returns the threshold for the largest time bucket <= elapsedMinutes.
     */
    fun minProfit(elapsedMinutes: Int): Double {
        var result = thresholds.last().second
        for ((mins, profit) in thresholds) {
            if (elapsedMinutes >= mins) {
                result = profit
            } else {
                break
            }
        }
        return result
    }

    /**
     * Check if ROI exit condition is met.
     * @param elapsedMinutes Minutes since trade entry
     * @param currentProfitPercent Current profit as decimal (0.05 = 5%)
     */
    fun shouldExit(elapsedMinutes: Int, currentProfitPercent: Double): Boolean =
        currentProfitPercent >= minProfit(elapsedMinutes)
}

/**
 * Stoploss rule definition.
 * Fixed percentage loss threshold for exit.
 *
 * SampleStrategy stoploss: -3% (exit if loss exceeds 3%)
 */
data class StoplossRule(
    val stoplossPercent: Double  // Negative value, e.g. -0.03 for -3%
) {
    companion object {
        /** SampleStrategy default stoploss: -3% */
        fun sampleStrategy(): StoplossRule = StoplossRule(-0.03)

        /** Tight stoploss: -1% */
        fun tight(): StoplossRule = StoplossRule(-0.01)

        /** Wide stoploss: -10% */
        fun wide(): StoplossRule = StoplossRule(-0.10)
    }

    /**
     * Check if stoploss exit condition is met.
     * @param currentProfitPercent Current profit as decimal (-0.05 = -5% loss)
     */
    fun shouldExit(currentProfitPercent: Double): Boolean =
        currentProfitPercent <= stoplossPercent
}

/**
 * Trailing stoploss rule definition.
 * Locks in profits by trailing the highest profit seen.
 *
 * SampleStrategy trailing:
 * - trailing_stop: true
 * - trailing_only_offset_is_reached: true (activate after 5% profit)
 * - trailing_stop_positive: 0.025 (2.5% trailing stop)
 * - trailing_stop_positive_offset: 0.05 (activate after 5% profit)
 */
data class TrailingStoplossRule(
    val enabled: Boolean,
    val offsetIsReached: Boolean,      // Only trail after offset reached
    val trailingPercent: Double,       // Trailing distance (e.g. 0.025 = 2.5%)
    val offsetPercent: Double          // Activation threshold (e.g. 0.05 = 5%)
) {
    companion object {
        /** SampleStrategy default trailing stoploss */
        fun sampleStrategy(): TrailingStoplossRule = TrailingStoplossRule(
            enabled = true,
            offsetIsReached = true,
            trailingPercent = 0.025,
            offsetPercent = 0.05
        )

        /** Simple trailing: always active, no offset */
        fun simple(trailingPercent: Double = 0.03): TrailingStoplossRule =
            TrailingStoplossRule(
                enabled = true,
                offsetIsReached = false,
                trailingPercent = trailingPercent,
                offsetPercent = 0.0
            )

        /** Disabled trailing stoploss */
        fun disabled(): TrailingStoplossRule = TrailingStoplossRule(
            enabled = false,
            offsetIsReached = false,
            trailingPercent = 0.0,
            offsetPercent = 0.0
        )
    }

    /**
     * Check if trailing stoploss exit condition is met.
     * @param currentProfitPercent Current profit as decimal
     * @param maxProfitPercent Maximum profit seen during trade
     */
    fun shouldExit(currentProfitPercent: Double, maxProfitPercent: Double): Boolean {
        if (!enabled) return false

        // Check if offset threshold is reached (if required)
        if (offsetIsReached && maxProfitPercent < offsetPercent) {
            return false  // Trailing not yet activated
        }

        // Calculate trailing stop level
        val trailingStopLevel = maxProfitPercent - trailingPercent

        // Exit if current profit falls below trailing stop
        return currentProfitPercent <= trailingStopLevel
    }
}

/**
 * Combined exit rule set — evaluates ROI, stoploss, and trailing stop together.
 *
 * Usage:
 * ```
 * val rules = ExitRuleSet.sampleStrategy()
 * if (rules.shouldExit(elapsedMinutes, currentProfit, maxProfit)) {
 *     // Execute exit
 * }
 * ```
 */
data class ExitRuleSet(
    val roi: RoiRule,
    val stoploss: StoplossRule,
    val trailing: TrailingStoplossRule
) {
    companion object {
        /** SampleStrategy complete exit rule set */
        fun sampleStrategy(): ExitRuleSet = ExitRuleSet(
            roi = RoiRule.sampleStrategy(),
            stoploss = StoplossRule.sampleStrategy(),
            trailing = TrailingStoplossRule.sampleStrategy()
        )

        /** Conservative: tight stops, quick exits */
        fun conservative(): ExitRuleSet = ExitRuleSet(
            roi = RoiRule.conservative(),
            stoploss = StoplossRule.tight(),
            trailing = TrailingStoplossRule.disabled()
        )

        /** Aggressive: wide stops, let winners run */
        fun aggressive(): ExitRuleSet = ExitRuleSet(
            roi = RoiRule.aggressive(),
            stoploss = StoplossRule.wide(),
            trailing = TrailingStoplossRule.simple(0.05)
        )
    }

    /**
     * Evaluate all exit conditions.
     * @param elapsedMinutes Minutes since trade entry
     * @param currentProfitPercent Current profit as decimal
     * @param maxProfitPercent Maximum profit seen during trade
     * @return true if ANY exit condition is met
     */
    fun shouldExit(
        elapsedMinutes: Int,
        currentProfitPercent: Double,
        maxProfitPercent: Double
    ): Boolean {
        // Stoploss always active (protects against catastrophic loss)
        if (stoploss.shouldExit(currentProfitPercent)) {
            return true
        }

        // Trailing stoploss (if enabled and activated)
        if (trailing.shouldExit(currentProfitPercent, maxProfitPercent)) {
            return true
        }

        // ROI (time-based profit target)
        if (roi.shouldExit(elapsedMinutes, currentProfitPercent)) {
            return true
        }

        return false
    }

    /**
     * Get the reason for exit (first matching condition).
     */
    fun exitReason(
        elapsedMinutes: Int,
        currentProfitPercent: Double,
        maxProfitPercent: Double
    ): String? {
        if (stoploss.shouldExit(currentProfitPercent)) return "STOPLOSS"
        if (trailing.shouldExit(currentProfitPercent, maxProfitPercent)) return "TRAILING"
        if (roi.shouldExit(elapsedMinutes, currentProfitPercent)) return "ROI"
        return null
    }
}

/**
 * Entry signal conditions — DSEL for entry logic.
 *
 * SampleStrategy entry conditions (long):
 * - RSI crosses above 30
 * - TEMA <= BB middle (guard)
 * - TEMA is rising (guard)
 * - Volume > 0
 */
data class EntryCondition(
    val rsiCrossAbove: Double?,      // RSI crosses above this value
    val temaBelowBbMiddle: Boolean,  // Guard: TEMA below BB middle
    val temaRising: Boolean,         // Guard: TEMA is rising
    val volumePositive: Boolean      // Guard: Volume > 0
) {
    companion object {
        /** SampleStrategy long entry condition */
        fun sampleLong(): EntryCondition = EntryCondition(
            rsiCrossAbove = 30.0,
            temaBelowBbMiddle = true,
            temaRising = true,
            volumePositive = true
        )

        /** SampleStrategy short entry condition */
        fun sampleShort(): EntryCondition = EntryCondition(
            rsiCrossAbove = 70.0,
            temaBelowBbMiddle = false,  // TEMA above BB middle for short
            temaRising = false,         // TEMA falling for short
            volumePositive = true
        )
    }
}

/**
 * Exit signal conditions — DSEL for exit logic (separate from ROI/stoploss).
 *
 * SampleStrategy exit conditions (long):
 * - RSI crosses above 70
 * - TEMA > BB middle (guard)
 * - TEMA is falling (guard)
 * - Volume > 0
 */
data class ExitSignalCondition(
    val rsiCrossAbove: Double?,      // RSI crosses above this value
    val temaAboveBbMiddle: Boolean,  // Guard: TEMA above BB middle
    val temaFalling: Boolean,        // Guard: TEMA is falling
    val volumePositive: Boolean      // Guard: Volume > 0
) {
    companion object {
        /** SampleStrategy long exit condition */
        fun sampleLong(): ExitSignalCondition = ExitSignalCondition(
            rsiCrossAbove = 70.0,
            temaAboveBbMiddle = true,
            temaFalling = true,
            volumePositive = true
        )

        /** SampleStrategy short exit condition */
        fun sampleShort(): ExitSignalCondition = ExitSignalCondition(
            rsiCrossAbove = 30.0,
            temaAboveBbMiddle = false,  // TEMA below BB middle for short exit
            temaFalling = false,        // TEMA rising for short exit
            volumePositive = true
        )
    }
}
