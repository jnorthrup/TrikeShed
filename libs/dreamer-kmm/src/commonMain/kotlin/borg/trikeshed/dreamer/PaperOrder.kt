package borg.trikeshed.dreamer

/**
 * Simulated order with a lifecycle: PENDING → FILLED | CANCELLED | REJECTED.
 *
 * In SHADOW mode the [TradingEngine] records these without placing real orders.
 * In LIVE mode they flow through [ApiClient.placeBuy] / [ApiClient.placeSell].
 *
 * @param symbol   trading pair symbol, e.g. "BTCUSDT"
 * @param quantity order quantity (always positive for a buy, negative for sell — use a separate sign convention)
 * @param price    limit price at which the order was submitted
 * @param status   current lifecycle status
 */
data class PaperOrder(
    val symbol: String,
    val quantity: Double,
    val price: Double,
    val status: OrderStatus = OrderStatus.PENDING,
)
