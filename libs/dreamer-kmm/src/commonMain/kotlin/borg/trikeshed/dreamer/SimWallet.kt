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

enum class WalletAction {
    RECORD,
    ORDER_ACCEPTED,
    ORDER_REJECTED,
    ORDER_CANCELLED,
    ORDER_FILLED,
    MARK_TO_MARKET,
    SIGNAL,
    RESET,
}

data class WalletJournalEntry(
    val index: Int,
    val action: WalletAction,
    val symbol: String,
    val quantity: Double = 0.0,
    val price: Double = 0.0,
    val free: Map<String, Double> = emptyMap(),
    val locked: Map<String, Double> = emptyMap(),
    val realized: Map<String, Double> = emptyMap(),
    val netValue: Double = 0.0,
    val note: String = "",
    val at: Long = 0L,
)

class SimWallet {
    public val balances = mutableMapOf<String, Double>()
    public val locked = mutableMapOf<String, Double>()
    public val pending = mutableListOf<Order>()
    public val realized = mutableMapOf<String, Double>()
    public val costBasis = mutableMapOf<String, Double>()
    public val journal = mutableListOf<WalletJournalEntry>()
    public var peakNetValue = 0.0
    public var orderIdCounter = 0

    fun record(symbol: String, quantity: Double, costBasis: Double = 0.0) {
        balances[symbol] = (balances[symbol] ?: 0.0) + quantity
        if (costBasis != 0.0) this.costBasis[symbol] = (this.costBasis[symbol] ?: 0.0) + costBasis
        appendJournal(WalletAction.RECORD, symbol, quantity = quantity, note = "record balance")
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
            if (available < cost) {
                appendJournal(WalletAction.ORDER_REJECTED, "$base$quote", quantity, price, "insufficient $quote")
                return null
            }

            locked[quote] = (locked[quote] ?: 0.0) + cost
            balances[quote] = (balances[quote] ?: 0.0) - cost
        } else {
            val available = freeBalance(base)
            if (available < quantity) {
                appendJournal(WalletAction.ORDER_REJECTED, "$base$quote", quantity, price, "insufficient $base")
                return null
            }

            locked[base] = (locked[base] ?: 0.0) + quantity
            balances[base] = (balances[base] ?: 0.0) - quantity
        }

        val orderId = "order-${++orderIdCounter}"
        val order = Order(orderId, base, quote, side, type, price, quantity, 0L)
        pending.add(order)
        appendJournal(WalletAction.ORDER_ACCEPTED, "$base$quote", quantity, price, "${side.name} ${type.name} $orderId")
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
                appendJournal(WalletAction.ORDER_CANCELLED, "${order.base}${order.quote}", order.quantity, order.price, order.id)
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
                appendJournal(WalletAction.ORDER_FILLED, orderSymbol, order.quantity, order.price, order.id)
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

    fun markToMarket(prices: Map<String, Double>, note: String = ""): Double {
        val total = worth(prices)
        appendJournal(WalletAction.MARK_TO_MARKET, "portfolio", netValue = total, note = note)
        return total
    }

    fun recordSignal(symbol: String, note: String, price: Double = 0.0, quantity: Double = 0.0) {
        appendJournal(WalletAction.SIGNAL, symbol, quantity = quantity, price = price, note = note)
    }

    fun journal(): List<WalletJournalEntry> = journal.toList()

    fun peakNetValue(): Double = peakNetValue

    fun autoDrawdown(): Double {
        // This usually requires a current worth to be calculated first or passed
        return 0.0 // Placeholder for more complex drawdown logic if needed
    }

    // Route fiat balances to canonical fiat#0 slot (non-destructive if not present).
    // This helps harness IO unify on a primary fiat channel without changing existing symbols.
    fun routeFiatToFiat0(fiatSymbol: String) {
        val amt = balances.remove(fiatSymbol) ?: 0.0
        if (amt != 0.0) balances["fiat#0"] = (balances["fiat#0"] ?: 0.0) + amt
        val lockedAmt = locked.remove(fiatSymbol) ?: 0.0
        if (lockedAmt != 0.0) locked["fiat#0"] = (locked["fiat#0"] ?: 0.0) + lockedAmt
        val realizedAmt = realized.remove(fiatSymbol) ?: 0.0
        if (realizedAmt != 0.0) realized["fiat#0"] = (realized["fiat#0"] ?: 0.0) + realizedAmt
        val cost = costBasis.remove(fiatSymbol) ?: 0.0
        if (cost != 0.0) costBasis["fiat#0"] = (costBasis["fiat#0"] ?: 0.0) + cost
    }

    fun reset() {
        balances.clear()
        locked.clear()
        pending.clear()
        realized.clear()
        costBasis.clear()
        peakNetValue = 0.0
        appendJournal(WalletAction.RESET, "wallet", note = "reset")
    }

    public fun appendJournal(
        action: WalletAction,
        symbol: String,
        quantity: Double = 0.0,
        price: Double = 0.0,
        note: String = "",
        netValue: Double = 0.0,
    ) {
        journal += WalletJournalEntry(
            index = journal.size,
            action = action,
            symbol = symbol,
            quantity = quantity,
            price = price,
            free = balances.sortedSnapshot(),
            locked = locked.sortedSnapshot(),
            realized = realized.sortedSnapshot(),
            netValue = netValue,
            note = note,
        )
    }

    public fun Map<String, Double>.sortedSnapshot(): Map<String, Double> =
        keys.sorted().associateWith { key -> this[key] ?: 0.0 }
}
