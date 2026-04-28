package borg.trikeshed.dreamer

enum class OrderSide { BUY, SELL }
enum class OrderType { LIMIT, MARKET }

data class Order(
    val id: String,
    val base: String,
    val quote: String,
    val side: OrderSide,
    val type: OrderType,
    val price: Double,
    val quantity: Double,
    val placedAt: Long
)

data class FilledOrder(
    val order: Order,
    val fillPrice: Double,
    val fillQuantity: Double,
    val filledAt: Long
)

class SimWallet {
    private val balances = mutableMapOf<String, Double>()
    private val locked = mutableMapOf<String, Double>()
    private val pending = mutableListOf<Order>()
    private val realized = mutableMapOf<String, Double>()
    private val costBasis = mutableMapOf<String, Double>()
    private var peakNetValue = 0.0
    private var orderIdCounter = 0

    fun record(symbol: String, quantity: Double, costBasis: Double = 0.0) {
        balances[symbol] = (balances[symbol] ?: 0.0) + quantity
    }

    fun placeOrder(
        base: String,
        quote: String,
        side: OrderSide,
        type: OrderType,
        price: Double,
        quantity: Double
    ): Order? {
        if (side == OrderSide.BUY) {
            val cost = price * quantity
            val available = freeBalance(quote)
            if (available < cost) return null

            locked[quote] = (locked[quote] ?: 0.0) + cost
            balances[quote] = (balances[quote] ?: 0.0) - cost
        } else {
            val available = freeBalance(base)
            if (available < quantity) return null

            locked[base] = (locked[base] ?: 0.0) + quantity
            balances[base] = (balances[base] ?: 0.0) - quantity
        }

        val orderId = "order-${++orderIdCounter}"
        val order = Order(orderId, base, quote, side, type, price, quantity, 0L)
        pending.add(order)
        return order
    }

    fun cancelOrder(orderId: String) {
        val it = pending.iterator()
        while (it.hasNext()) {
            val order = it.next()
            if (order.id == orderId) {
                // Release locked funds
                if (order.side == OrderSide.BUY) {
                    val cost = order.price * order.quantity
                    locked[order.quote] = (locked[order.quote] ?: 0.0) - cost
                    balances[order.quote] = (balances[order.quote] ?: 0.0) + cost
                } else {
                    locked[order.base] = (locked[order.base] ?: 0.0) - order.quantity
                    balances[order.base] = (balances[order.base] ?: 0.0) + order.quantity
                }
                it.remove()
                break
            }
        }
    }

    fun processBar(symbol: String, high: Double, low: Double, close: Double): List<FilledOrder> {
        val fills = mutableListOf<FilledOrder>()
        val it = pending.iterator()
        while (it.hasNext()) {
            val order = it.next()

            val orderSymbol = "${order.base}${order.quote}"
            if (orderSymbol != symbol) continue

            // Very simple limit order fill logic
            val crossesBuy = order.side == OrderSide.BUY && low <= order.price
            val crossesSell = order.side == OrderSide.SELL && high >= order.price

            if (crossesBuy || crossesSell) {
                fills.add(FilledOrder(order, order.price, order.quantity, 0L))
                it.remove()

                // Release locked funds and update final balance
                if (order.side == OrderSide.BUY) {
                    val cost = order.price * order.quantity
                    locked[order.quote] = (locked[order.quote] ?: 0.0) - cost

                    // Update average cost basis
                    val currentQty = balances[order.base] ?: 0.0
                    val currentCost = costBasis[order.base] ?: 0.0
                    costBasis[order.base] = currentCost + cost
                    balances[order.base] = currentQty + order.quantity
                } else {
                    // Realize PnL
                    val totalQtyBeforeFill = (balances[order.base] ?: 0.0) + (locked[order.base] ?: 0.0)
                    val totalCost = costBasis[order.base] ?: 0.0
                    val avgPrice = if (totalQtyBeforeFill > 0) totalCost / totalQtyBeforeFill else 0.0

                    val profit = (order.price - avgPrice) * order.quantity
                    realized[order.base] = (realized[order.base] ?: 0.0) + profit

                    // Update cost basis proportionally
                    costBasis[order.base] = totalCost * (1.0 - (order.quantity / totalQtyBeforeFill))

                    locked[order.base] = (locked[order.base] ?: 0.0) - order.quantity
                    balances[order.quote] = (balances[order.quote] ?: 0.0) + (order.price * order.quantity)
                }
            }
        }
        return fills
    }

    fun pendingOrders(symbol: String? = null): List<Order> {
        return if (symbol == null) pending.toList()
        else pending.filter { it.base == symbol || it.quote == symbol }
    }

    fun freeBalance(symbol: String): Double {
        return balances[symbol] ?: 0.0
    }

    fun lockedBalance(symbol: String): Double {
        return locked[symbol] ?: 0.0
    }

    fun netQuantity(symbol: String): Double {
        return (balances[symbol] ?: 0.0) + (locked[symbol] ?: 0.0)
    }

    fun realizedPnl(symbol: String): Double {
        return realized[symbol] ?: 0.0
    }

    fun unrealizedPnl(symbol: String, prices: Map<String, Double>): Double {
        val qty = netQuantity(symbol)
        if (qty == 0.0) return 0.0
        val price = prices[symbol] ?: return 0.0
        val totalCost = costBasis[symbol] ?: 0.0
        return (price * qty) - totalCost
    }

    fun worth(prices: Map<String, Double>): Double {
        var total = balances["USDT"] ?: 0.0
        total += locked["USDT"] ?: 0.0

        balances.forEach { (sym, qty) ->
            if (sym != "USDT") {
                val price = prices[sym] ?: 0.0
                total += qty * price
            }
        }
        locked.forEach { (sym, qty) ->
            if (sym != "USDT") {
                val price = prices[sym] ?: 0.0
                total += qty * price
            }
        }

        if (total > peakNetValue) peakNetValue = total
        return total
    }

    fun peakNetValue(): Double = peakNetValue

    fun autoDrawdown(): Double {
        // This usually requires a current worth to be calculated first or passed
        return 0.0 // Placeholder for more complex drawdown logic if needed
    }

    fun reset() {
        balances.clear()
        locked.clear()
        pending.clear()
        realized.clear()
        costBasis.clear()
        peakNetValue = 0.0
    }
}
