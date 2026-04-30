package borg.trikeshed.dreamer

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.asString
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * KlineCsvParser: Binance archive CSV → [ExtendedKline].
 *
 * Binance kline CSV has 12 columns (per line):
 *   0  Open_time                  (Long, ms timestamp)
 *   1  Open                      (Double)
 *   2  High                      (Double)
 *   3  Low                       (Double)
 *   4  Close                     (Double)
 *   5  Volume                    (Double)
 *   6  Close_time                (Long, ms timestamp)
 *   7  Quote_asset_volume         (Double)
 *   8  Number_of_trades          (Int)
 *   9  Taker_buy_base_volume     (Double)
 *  10  Taker_buy_quote_volume    (Double)
 *  11  Ignore                    (ignored)
 *
 * The first line is a header row (skipped — detected by non-digit first char).
 * Blank or malformed lines are skipped.
 *
 * @param csvText raw CSV text as a [Series]<[Char]>
 * @param symbol  Binance symbol string, e.g. "BTCUSDT"
 * @param timespan [TimeSpan] for all rows in this archive
 * @return [Series]<[ExtendedKline]> — one element per data row
 */
fun klinesFromCsv(
    csvText: Series<Char>,
    symbol: String,
    timespan: TimeSpan,
): Series<ExtendedKline> {
    // Copy into a CharArray so we can use simple index operators without
    // Kotlin 2.0 type-resolution issues on Series<Char> subscript access.
    val n = csvText.size
    // Convert Series<Char> to String to avoid Kotlin 2.0 subscript resolution issues,
    // then explode into a mutable CharArray for efficient line-by-line parsing.
    val csvString: String = csvText.asString()
    val arr = csvString.toCharArray()
    val result = mutableListOf<ExtendedKline>()

    var pos = 0
    while (pos < n) {
        // find line end
        var lineEnd = pos
        while (lineEnd < n) {
            val c = arr[lineEnd]
            if (c == '\n' || c == '\r') break
            lineEnd++
        }

        // trim trailing whitespace
        var contentEnd = lineEnd
        while (contentEnd > pos) {
            val c = arr[contentEnd - 1]
            if (!c.isWhitespace()) break
            contentEnd--
        }

        if (contentEnd == pos) {
            pos = skipNl(arr, lineEnd, n)
            continue
        }

        // detect header: first char is a digit
        if (!arr[pos].isDigit()) {
            pos = skipNl(arr, lineEnd, n)
            continue
        }

        // build field-start offsets
        val starts = mutableListOf<Int>()
        starts.add(pos)
        var p = pos
        while (p < contentEnd) {
            if (arr[p] == ',') starts.add(p + 1)
            p++
        }

        // parse ExtendedKline from this line
        val ek = parseKlineLine(arr, starts, contentEnd, symbol, timespan)
        if (ek != null) result.add(ek)

        pos = skipNl(arr, lineEnd, n)
    }

    val count = result.size
    return count j { i -> result[i] }
}
 public fun skipNl(arr: CharArray, pos: Int, n: Int): Int {
    var p = pos
    if (p < n && arr[p] == '\r') p++
    if (p < n && arr[p] == '\n') p++
    return p
}
 public fun parseKlineLine(
    arr: CharArray,
    starts: List<Int>,
    lineEnd: Int,
    symbol: String,
    timespan: TimeSpan,
): ExtendedKline? {
    fun field(idx: Int): String? {
        if (idx < 0 || idx >= starts.size) return null
        val s = starts[idx]
        val e = if (idx + 1 < starts.size) starts[idx + 1] - 1 else lineEnd
        var b = s
        while (b < e && arr[b].isWhitespace()) b++
        var t = e
        while (t > b) {
            val c = arr[t - 1]
            if (!c.isWhitespace()) break
            t--
        }
        if (t <= b) return null
        return arr.concatToString(b, t)
    }

    val f0 = field(0)?.toLongOrNull() ?: return null
    val f1 = field(1)?.toDoubleOrNull() ?: return null
    val f2 = field(2)?.toDoubleOrNull() ?: return null
    val f3 = field(3)?.toDoubleOrNull() ?: return null
    val f4 = field(4)?.toDoubleOrNull() ?: return null
    val f5 = field(5)?.toDoubleOrNull() ?: return null
    val f6 = field(6)?.toLongOrNull() ?: return null
    val f7 = field(7)?.toDoubleOrNull() ?: return null
    val f8 = field(8)?.toIntOrNull() ?: return null
    val f9 = field(9)?.toDoubleOrNull() ?: return null
    val f10 = field(10)?.toDoubleOrNull() ?: return null

    return ExtendedKline(
        symbol = symbol,
        timespan = timespan,
        openTime = f0,
        open = f1,
        high = f2,
        low = f3,
        close = f4,
        volume = f5,
        closeTime = f6,
        quoteAssetVolume = f7,
        trades = f8,
        takerBuyBaseVolume = f9,
        takerBuyQuoteVolume = f10,
    )
}
