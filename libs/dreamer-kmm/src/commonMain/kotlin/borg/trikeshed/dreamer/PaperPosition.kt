package borg.trikeshed.dreamer

/**
 * Simulated open position tracking.
 *
 * Holds quantity and entry price, computes unrealized PnL against current price.
 *
 * @param symbol      trading pair symbol, e.g. "BTCUSDT"
 * @param quantity    raw quantity held (never negative)
 * @param entryPrice  price at which the position was opened
 */
data class PaperPosition(
    val symbol: String,
    val quantity: Double,
    val entryPrice: Double,
) {
    /**
     * Unrealized PnL = (currentPrice - entryPrice) * quantity.
     * Positive when price is above entry, negative when below.
     */
    fun unrealizedPnL(currentPrice: Double): Double =
        (currentPrice - entryPrice) * quantity
}
