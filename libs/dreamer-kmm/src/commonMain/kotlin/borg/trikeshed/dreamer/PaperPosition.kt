package borg.trikeshed.dreamer

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey

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
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<PaperPosition>()
    override val key: AsyncContextKey<PaperPosition> get() = Key

    /**
     * Unrealized PnL = (currentPrice - entryPrice) * quantity.
     * Positive when price is above entry, negative when below.
     */
    fun unrealizedPnL(currentPrice: Double): Double =
        (currentPrice - entryPrice) * quantity
}
