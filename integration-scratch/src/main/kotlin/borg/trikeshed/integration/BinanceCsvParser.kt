package borg.trikeshed.integration

import borg.trikeshed.couch.kline.Kline
import borg.trikeshed.couch.kline.TimeSpan
import java.io.InputStream

/**
 * BinanceCsvParser: parses Binance OHLCV CSV lines into [Kline] objects.
 *
 * Binance CSV format (12 fields):
 *   open_time, open, high, low, close, volume, close_time,
 *   quote_volume, trades, taker_buy_base, taker_buy_quote, ignore
 *
 * We only consume fields 0-5 (OHLCV) for now.
 */
class BinanceCsvParser(
   val symbol: String,
   val interval: String,
) {
   val timespan: TimeSpan = intervalToTimeSpan(interval)

    /** Parse a single CSV line. Returns null if blank or malformed. */
    fun parseCsvLine(line: String): Kline? {
        if (line.isBlank()) return null
        val fields = line.split(',')
        if (fields.size < 6) return null

        return try {
            Kline(
                symbol = symbol,
                timespan = timespan,
                openTime = fields[0].toLong(),
                open = fields[1].toDouble(),
                high = fields[2].toDouble(),
                low = fields[3].toDouble(),
                close = fields[4].toDouble(),
                volume = fields[5].toDouble(),
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Parse all lines in a CSV string into a list of Kline. */
    fun parseCsv(csv: String): List<Kline> =
        csv.lineSequence()
            .mapNotNull { parseCsvLine(it) }
            .toList()

   fun intervalToTimeSpan(interval: String): TimeSpan = when (interval) {
        "1m"  -> TimeSpan.Minutes1
        "3m"  -> TimeSpan.Minutes3
        "5m"  -> TimeSpan.Minutes5
        "15m" -> TimeSpan.Minutes15
        "30m" -> TimeSpan.Minutes30
        "1h"  -> TimeSpan.Hours1
        "2h"  -> TimeSpan.Hours2
        "4h"  -> TimeSpan.Hours4
        "6h"  -> TimeSpan.Hours6
        "8h"  -> TimeSpan.Hours8
        "12h" -> TimeSpan.Hours12
        "1d"  -> TimeSpan.Days1
        "3d"  -> TimeSpan.Days3
        "1w"  -> TimeSpan.Weeks1
        "1M"  -> TimeSpan.Months1
        else  -> TimeSpan.Hours1
    }
}
