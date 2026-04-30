package dreamer.dashboard

import borg.trikeshed.dreamer.StochasticBagSpanTrainer
import borg.trikeshed.dreamer.StochasticTrainingConfig
import borg.trikeshed.dreamer.StochasticTrainingSnapshot
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
 * Visible state for the archive replay -> stochastic bag/span -> Dreamer paper
 * training loop. The work runs continuously until the UI is closed.
 */
class DashboardState {
    private val trainer = StochasticBagSpanTrainer(
        StochasticTrainingConfig(
            rowsPerSeries = 720,
            populationSize = 8,
            spanLength = 64,
            seed = 12_301,
        )
    )

    private var started = false
    private var running = true

    var cashBalance: Double = 10_000.0
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
    var trainingLifecycle: Int = 2  // 2 = ACTIVE
    val trainingLog = mutableListOf<String>()
    var trainingLogRendered: Int = 0

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
        bestFitness = snapshot.bestFitness
        barsReplayed = snapshot.totalCycles.toLong() * snapshot.evaluations.toLong()
        genomeTakePercent = snapshot.championTakePercent
        genomeMinSurplus = snapshot.championMinSurplus
        genomeRebalanceTrigger = snapshot.championRebalanceTrigger

        tradeLog.add(
            "gen=${snapshot.generation} value=${snapshot.bestTotalValue.fmt()} " +
                "pnl=${snapshot.bestProfit.fmt()} trades=${snapshot.bestTrades}"
        )
        snapshot.sampleSpans.forEach { tradeLog.add("span $it") }
        trim(tradeLog)

        trainingLog.add(
            "gen=${snapshot.generation} fitness=${snapshot.bestFitness.num()} " +
                "windows=${snapshot.totalWindows.fmt()} spans=${snapshot.totalSpans.fmt()}"
        )
        snapshot.sampleWindows.forEach { trainingLog.add("bag $it") }
        trim(trainingLog)
    }

    private fun trim(log: MutableList<String>) {
        while (log.size > 80) log.removeAt(0)
    }
}
