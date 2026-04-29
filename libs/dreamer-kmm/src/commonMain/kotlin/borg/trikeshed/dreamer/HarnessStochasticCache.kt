package borg.trikeshed.dreamer

import borg.trikeshed.miniduck.HarnessStochasticCache as MiniduckCache
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.indicator.Stochastic

/**
 * Legacy wrapper: delegate dreamer-facing API to the miniduck implementation so
 * the cache lives in libs:miniduck (avoids circular module dependencies).
 */
object HarnessStochasticCache {
    suspend fun ensureCached(
        symbol: String,
        timeframe: String,
        kPeriod: Int = 14,
        dPeriod: Int = 3,
        cursorSupplier: () -> MiniCursor,
    ) {
        MiniduckCache.ensureCached(symbol, timeframe, kPeriod, dPeriod, cursorSupplier)
    }

    fun get(symbol: String, timeframe: String, kPeriod: Int = 14, dPeriod: Int = 3): Stochastic.Result? {
        return MiniduckCache.get(symbol, timeframe, kPeriod, dPeriod)
    }
}
