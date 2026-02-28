@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package borg.trikeshed.duck

import kotlinx.cinterop.*
import duckdb.*

/**
 * C-FFI Ingestion Layer for Orchestrator (Python) to Engine (Kotlin)
 */

@CName("trikeshed_ingest_ohlcv")
fun ingestOHLCV(
    symbol: CPointer<ByteVar>?,
    timestamp: Long,
    open: Double,
    high: Double,
    low: Double,
    close: Double,
    volume: Double
): Int {
    val sym = symbol?.toKString() ?: return -1
    DuckMuxer.ingestOHLCV(sym, timestamp, open, high, low, close, volume)
    return 0
}

@CName("trikeshed_ingest_wallet")
fun ingestWallet(
    symbol: CPointer<ByteVar>?,
    balance: Double,
    available: Double,
    stake: Double
): Int {
    val sym = symbol?.toKString() ?: return -1
    DuckMuxer.ingestWallet(sym, balance, available, stake)
    return 0
}

@CName("trikeshed_ingest_trade")
fun ingestTrade(
    symbol: CPointer<ByteVar>?,
    entryPrice: Double,
    amount: Double,
    isShort: Boolean,
    unrealizedPnl: Double
): Int {
    val sym = symbol?.toKString() ?: return -1
    DuckMuxer.ingestTrade(sym, entryPrice, amount, isShort, unrealizedPnl)
    return 0
}

/**
 * Signal Extraction Bridge
 * Returns buy/sell action (-1 for sell, 0 for hold, 1 for buy)
 */
@CName("trikeshed_get_action")
fun getAction(symbol: CPointer<ByteVar>?): Int {
    val sym = symbol?.toKString() ?: return 0
    return DuckMuxer.getAction(sym)
}

// Helper
private fun CPointer<ByteVar>.toKString(): String? =
    this.readBytes().decodeToString().takeIf { it.isNotEmpty() }

private fun CPointer<ByteVar>.readBytes(): ByteArray {
    var length = 0
    while (this[length].toInt() != 0) {
        length++
    }
    val bytes = ByteArray(length)
    for (i in 0 until length) {
        bytes[i] = this[i]
    }
    return bytes
}
