package borg.trikeshed.dreamer

/**
 * Simulated account state: cash balance and open positions.
 *
 * In SHADOW mode the [TradingEngine] updates [cashBalance] and [holdings] directly.
 * In LIVE mode a real exchange API call would do the same.
 *
 * @param balance  starting cash balance (USD-equivalent)
 */
data class PaperAccount(
    val balance: Double,
)
