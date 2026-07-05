package borg.trikeshed.integration

import borg.trikeshed.lib.size
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

data class BinanceStochasticMainArgs(
    val symbol: String = "BTCUSDT",
    val interval: String = "1h",
    val startDate: LocalDate = LocalDate.parse("2024-01-01"),
    val endDate: LocalDate = LocalDate.parse("2024-01-01"),
    val maxConcurrentFetches: Int = 4,
)

fun parseBinanceStochasticMainArgs(args: Array<String>): BinanceStochasticMainArgs {
    var parsed = BinanceStochasticMainArgs()
    var i = 0
    while (i < args.size) {
        val name = args[i]
        fun value(): String {
            require(i + 1 < args.size) { "missing value for $name" }
            return args[++i]
        }
        parsed = when (name) {
            "--symbol" -> parsed.copy(symbol = value())
            "--interval" -> parsed.copy(interval = value())
            "--start" -> parsed.copy(startDate = LocalDate.parse(value()))
            "--end" -> parsed.copy(endDate = LocalDate.parse(value()))
            "--max-concurrent-fetches" -> parsed.copy(maxConcurrentFetches = value().toInt())
            else -> throw IllegalArgumentException("unknown argument: $name")
        }
        i++
    }
    require(!parsed.startDate.isAfter(parsed.endDate)) { "--start must be on or before --end" }
    require(parsed.maxConcurrentFetches > 0) { "--max-concurrent-fetches must be positive" }
    return parsed
}

suspend fun runBinanceStochasticKlineCache(
    args: BinanceStochasticMainArgs,
    provider: BinanceKlineProvider = BinanceKlineSourceProvider(
        maxConcurrentFetches = args.maxConcurrentFetches,
    ),
): BinanceStochasticKline = ProcessLocalBinanceStochasticCache.getOrLoad(
    key = BinanceKlineKey(
        symbol = args.symbol,
        interval = args.interval,
        startDate = args.startDate,
        endDate = args.endDate,
    ),
    provider = provider,
)

fun main(rawArgs: Array<String>) = runBlocking {
    val args = parseBinanceStochasticMainArgs(rawArgs)
    val loaded = runBinanceStochasticKlineCache(args)
    println("Loaded ${loaded.cursor.size} Binance klines for ${loaded.key.kline.symbol} ${loaded.key.kline.interval}")
    println("Stochastic rows=${loaded.stochastic.k.size}")
}
