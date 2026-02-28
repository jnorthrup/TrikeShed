package borg.trikeshed.duck

import borg.trikeshed.lib.Series

object DuckMuxer {
    private val ohlcvMap = mutableMapOf<String, MutableList<Candle>>()
    private val walletMap = mutableMapOf<String, WalletState>()
    private val tradeMap = mutableMapOf<String, TradeState>()

    fun ingestOHLCV(sym: String, ts: Long, o: Double, h: Double, l: Double, c: Double, v: Double) {
        ohlcvMap.getOrPut(sym) { mutableListOf() }.add(Candle(ts, o, h, l, c, v))
    }

    fun ingestWallet(sym: String, bal: Double, avail: Double, stake: Double) {
        walletMap[sym] = WalletState(bal, avail, stake)
    }

    fun ingestTrade(sym: String, price: Double, amt: Double, short: Boolean, pnl: Double) {
        tradeMap[sym] = TradeState(price, amt, short, pnl)
    }

    fun getAction(sym: String): Int {
        // Placeholder for model inference logic
        return 0 
    }

    fun getOHLCV(sym: String): List<Candle> = ohlcvMap[sym] ?: emptyList()
    fun getWallet(sym: String): WalletState? = walletMap[sym]
    fun getTrade(sym: String): TradeState? = tradeMap[sym]

    fun clear() {
        ohlcvMap.clear()
        walletMap.clear()
        tradeMap.clear()
    }
}

data class Candle(val timestamp: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
data class WalletState(val balance: Double, val available: Double, val stake: Double)
data class TradeState(val entryPrice: Double, val amount: Double, val isShort: Boolean, val unrealizedPnl: Double)
