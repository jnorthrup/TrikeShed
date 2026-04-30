package dreamer.terminal

import borg.trikeshed.dreamer.StochasticBagSpanTrainer
import borg.trikeshed.dreamer.StochasticTrainingConfig
import borg.trikeshed.dreamer.StochasticTrainingSnapshot
import borg.trikeshed.dreamer.BinanceVisionKlineFeed
import borg.trikeshed.dreamer.HarnessReplayInput
import borg.trikeshed.dreamer.TimeSpan
import borg.trikeshed.dreamer.klineSeriesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Number formatting for Kotlin/JS (no String.format). */
fun Double.fmt(d: Int = 2): String {
    val fixed = this.toFixed(d)
    // Add commas: 12345.67 → 12,345.67
    val parts = fixed.split('.')
    val intPart = parts[0].reversed().chunked(3).joinToString(",").reversed()
    return if (parts.size > 1) "$$intPart.${parts[1]}" else "$$intPart"
}

fun Long.fmt(): String {
    val s = this.toString().reversed().chunked(3).joinToString(",").reversed()
    return s
}

fun Int.fmt(): String = this.toLong().fmt()

fun Double.num(d: Int = 4): String = this.toFixed(d)

fun Double.percent(d: Int = 2): String = "${(this * 100.0).toFixed(d)}%"

// Kotlin/JS polyfill for toFixed
fun Double.toFixed(digits: Int): String {
    val mult = when (digits) {
        0 -> 1.0; 1 -> 10.0; 2 -> 100.0; 3 -> 1000.0; else -> 1.0
    }
    val rounded = kotlin.math.round(this * mult) / mult
    val str = rounded.toString()
    val dot = str.indexOf('.')
    return if (dot < 0) "$str.${"0".repeat(digits)}"
    else {
        val decimals = str.substring(dot + 1)
        if (decimals.length >= digits) str.substring(0, dot + 1 + digits)
        else "$str${"0".repeat(digits - decimals.length)}"
    }
}

/**
 * Visible state for the Dreamer archive replay -> stochastic bag/span -> paper
 * training loop. Work runs continuously until the terminal app is closed.
 */
class DreamerTerminalState {
    private val config = StochasticTrainingConfig(
        rowsPerSeries = 720,
        populationSize = 8,
        spanLength = 64,
        initialCapital = 100.0,
        seed = 12_301,
    )
    private val inputs = loadBinanceInputs(config)
    private val trainer = StochasticBagSpanTrainer(config, inputs)

    private var started = false
    private var running = true

    var initialCapital: Double = 100.0
    var feed: String = "Binance API klines"
    var pairList: String = inputs.joinToString(" ") { it.key.symbol }
    var cashBalance: Double = 100.0
    var finalTotalValue: Double = 0.0
    var bestFitness: Double = 0.0
    var totalTrades: Int = 0
    var totalWindows: Int = 0
    var totalSpans: Int = 0
    var totalCycles: Int = 0
    var pairCount: Int = 0
    var rowsPerSeries: Int = 0
    var populationSize: Int = 0
    var tradingLifecycle: Int = 2  // 2 = ACTIVE
    val tradeLog = mutableListOf<String>()
    var tradeLogRendered: Int = 0

    var barsReplayed: Long = 0L
    var genomeTakePercent: Double = 0.0
    var genomeMinSurplus: Double = 0.0
    var genomeRebalanceTrigger: Double = 0.0
    var genomeVersion: Int = 0
    var bestPnl: Double = 0.0
    var bestDrawdown: Double = 0.0
    var trainingLifecycle: Int = 2  // 2 = ACTIVE
    var activeSpan: String = ""
    var activePairSpan: String = ""

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            while (running) {
                trainingLifecycle = 2
                val snapshot = trainer.runGeneration()
                apply(snapshot)
                delay(25)
            }
        }
    }

    fun tick() {
        // Rendering is decoupled from training. The coroutine above mutates the
        // latest snapshot; blessed only needs a steady paint loop.
    }

    fun stop() {
        running = false
        tradingLifecycle = 4
        trainingLifecycle = 4
    }

    private fun apply(snapshot: StochasticTrainingSnapshot) {
        genomeVersion = snapshot.generation
        pairCount = snapshot.pairCount
        rowsPerSeries = snapshot.rowsPerSeries
        populationSize = snapshot.populationSize
        totalCycles = snapshot.totalCycles
        totalWindows = snapshot.totalWindows
        totalSpans = snapshot.totalSpans
        totalTrades = snapshot.bestTrades
        finalTotalValue = snapshot.bestTotalValue
        bestPnl = snapshot.bestProfit
        bestDrawdown = snapshot.bestDrawdown
        bestFitness = snapshot.bestFitness
        barsReplayed = snapshot.totalCycles.toLong() * snapshot.evaluations.toLong()
        genomeTakePercent = snapshot.championTakePercent
        genomeMinSurplus = snapshot.championMinSurplus
        genomeRebalanceTrigger = snapshot.championRebalanceTrigger
        activeSpan = snapshot.sampleWindows.firstOrNull().orEmpty()
        activePairSpan = snapshot.sampleSpans.firstOrNull().orEmpty()
    }
}

private fun loadBinanceInputs(config: StochasticTrainingConfig): List<HarnessReplayInput> {
    val root = nodeEnv("DREAMER_BINANCE_CACHE_ROOT") ?: "build/dreamer-binance-cache"
    val interval = config.timespan.binanceInterval
    val feed = BinanceVisionKlineFeed()
    return config.bases.map { base ->
        val key = klineSeriesKey(base, config.quote, config.timespan)
        val csv = readText("$root/${key.symbol}-$interval.csv")
        val parsed = feed.parseCachedCsv(key, csv)
        HarnessReplayInput(key, parsed.block)
    }
}

private fun readText(path: String): String =
    nodeFs().readFileSync(path, "utf8") as String

private fun nodeEnv(name: String): String? =
    js("typeof process !== 'undefined' && process.env ? process.env[name] : null") as String?

private fun nodeFs(): dynamic = js("require('fs')")
