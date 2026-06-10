package borg.trikeshed.dreamer

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class StochasticTrainingConfig(
    val bases: List<String> = listOf("BTC", "ETH", "SOL", "BNB", "ADA", "XRP"),
    val quote: String = "USDT",
    val timespan: TimeSpan = TimeSpan.Minutes1,
    val rowsPerSeries: Int = 720,
    val populationSize: Int = 8,
    val spanLength: Int = 64,
    val initialCapital: Double = 10_000.0,
    val seed: Int = 12_301,
    val mutationStep: Double = 0.015,
    val startOpenTime: Long = 1_704_067_200_000L,
)

data class StochasticTrainingSnapshot(
    val generation: Int,
    val pairCount: Int,
    val rowsPerSeries: Int,
    val populationSize: Int,
    val evaluations: Int,
    val bestFitness: Double,
    val bestTotalValue: Double,
    val bestProfit: Double,
    val bestDrawdown: Double,
    val bestTrades: Int,
    val totalCycles: Int,
    val totalWindows: Int,
    val totalSpans: Int,
    val championTakePercent: Double,
    val championMinSurplus: Double,
    val championRebalanceTrigger: Double,
    val sampleWindows: List<String>,
    val sampleSpans: List<String>,
)

private data class StochasticTrainingEvaluation(
    val genome: Genome,
    val run: HarnessRunResult,
    val fitness: Double,
)

class StochasticBagSpanTrainer(
    private val config: StochasticTrainingConfig = StochasticTrainingConfig(),
    private val inputs: List<HarnessReplayInput> = archiveInputs(config),
) {
    private var generation: Int = 0
    private var population: List<Genome> = initialPopulation()

    suspend fun runGeneration(): StochasticTrainingSnapshot {
        require(population.isNotEmpty()) { "population must not be empty" }
        val completedGeneration = generation + 1
        val evaluations = population.mapIndexed { index, genome ->
            val run = RealtimeHarness(
                genome = genome,
                initialCapital = config.initialCapital,
                mode = Mode.SHADOW,
                stochasticSeed = config.seed + completedGeneration * 4099 + index,
                stochasticSpanLength = config.spanLength,
            ).replay(inputs)
            StochasticTrainingEvaluation(
                genome = genome,
                run = run,
                fitness = run.trainingFitness(config.initialCapital, genome),
            )
        }.sortedByDescending { it.fitness }

        val best = evaluations.first()
        val snapshot = best.snapshot(completedGeneration, evaluations.size)
        population = nextPopulation(evaluations, Random(config.seed + completedGeneration * 9176))
        generation = completedGeneration
        return snapshot
    }

    private fun initialPopulation(): List<Genome> {
        val seed = defaultGenome()
        if (config.populationSize <= 1) return listOf(seed)
        val random = Random(config.seed)
        return List(config.populationSize) { index ->
            if (index == 0) seed.copyGenome() else mutate(seed, index, random)
        }
    }

    private fun nextPopulation(
        evaluations: List<StochasticTrainingEvaluation>,
        random: Random,
    ): List<Genome> {
        val elite = evaluations.first().genome.copyGenome()
        if (config.populationSize <= 1) return listOf(elite)
        val parents = evaluations.take(minOf(3, evaluations.size)).map { it.genome }
        return List(config.populationSize) { index ->
            when (index) {
                0 -> elite
                else -> mutate(parents[index % parents.size], index + generation, random)
            }
        }
    }

    private fun mutate(parent: Genome, salt: Int, random: Random): Genome {
        val keys = arrayOf(
            "HARVEST_TAKE_PERCENT",
            "MIN_SURPLUS_FOR_HARVEST",
            "FLAT_REBALANCE_TRIGGER_PERCENT",
            "FITNESS_DRAWDOWN_PENALTY",
            "MIN_ASSET_SURPLUS_FOR_PORTFOLIO_HARVEST",
        )
        val key = keys[salt % keys.size]
        val current = parent.getDouble(key)
        val signedStep = config.mutationStep * if (random.nextBoolean()) 1.0 else -1.0
        val jitter = random.nextDouble(0.25, 1.0)
        val next = parent.copyGenome()
        next[key] = when (key) {
            "HARVEST_TAKE_PERCENT" -> (current + signedStep * jitter).coerceIn(0.05, 0.95)
            "FITNESS_DRAWDOWN_PENALTY" -> (current + signedStep * jitter).coerceIn(0.10, 5.0)
            else -> (current + signedStep * jitter).coerceIn(0.001, 2.0)
        }
        return next
    }

    private fun StochasticTrainingEvaluation.snapshot(
        completedGeneration: Int,
        evaluationCount: Int,
    ): StochasticTrainingSnapshot {
        val tradeCount = run.cycles.count { it.result.anyTradesThisCycle }
        val totalWindows = run.cycles.sumOf { it.frame.bag.windows.size }
        val totalSpans = run.cycles.sumOf { it.frame.bag.spans.size }
        val lastBag = run.cycles.lastOrNull()?.frame?.bag
        val sampleWindows = lastBag?.windows.orEmpty().take(4).map { window ->
            "${window.key.symbol}[${window.start},${window.endExclusive}) rows=${window.rowCount} " +
                "t=${window.firstOpenTime}..${window.lastOpenTime}"
        }
        val sampleSpans = lastBag?.spans.orEmpty().take(4).map { span ->
            "${span.left.symbol}/${span.right.symbol} rows=${span.aRows}:${span.bRows}"
        }
        return StochasticTrainingSnapshot(
            generation = completedGeneration,
            pairCount = config.bases.size,
            rowsPerSeries = config.rowsPerSeries,
            populationSize = config.populationSize,
            evaluations = evaluationCount,
            bestFitness = fitness,
            bestTotalValue = run.finalTotalValue,
            bestProfit = run.finalTotalValue - config.initialCapital,
            bestDrawdown = run.maxDrawdown(config.initialCapital),
            bestTrades = tradeCount,
            totalCycles = run.cycles.size,
            totalWindows = totalWindows,
            totalSpans = totalSpans,
            championTakePercent = genome.getDouble("HARVEST_TAKE_PERCENT"),
            championMinSurplus = genome.getDouble("MIN_SURPLUS_FOR_HARVEST"),
            championRebalanceTrigger = genome.getDouble("FLAT_REBALANCE_TRIGGER_PERCENT"),
            sampleWindows = sampleWindows,
            sampleSpans = sampleSpans,
        )
    }

    private fun HarnessRunResult.trainingFitness(initialCapital: Double, genome: Genome): Double {
        val profit = if (initialCapital > 0.0) (finalTotalValue - initialCapital) / initialCapital else 0.0
        val trades = cycles.count { it.result.anyTradesThisCycle }.toDouble()
        val drawdownPenalty = maxDrawdown(initialCapital) * genome.getDouble("FITNESS_DRAWDOWN_PENALTY", 1.0)
        return profit + trades * 0.001 - drawdownPenalty
    }

    private fun HarnessRunResult.maxDrawdown(initialCapital: Double): Double {
        var peak = initialCapital
        var maxDrawdown = 0.0
        cycles.forEach { cycle ->
            if (cycle.totalValue > peak) {
                peak = cycle.totalValue
            } else if (peak > 0.0) {
                val drawdown = (peak - cycle.totalValue) / peak
                if (drawdown > maxDrawdown) maxDrawdown = drawdown
            }
        }
        return maxDrawdown
    }
}

private fun archiveInputs(config: StochasticTrainingConfig): List<HarnessReplayInput> {
    val feed = BinanceVisionKlineFeed()
    return config.bases.mapIndexed { index, base ->
        val key = klineSeriesKey(base, config.quote, config.timespan)
        val csv = generatedArchiveCsv(
            symbol = key.symbol,
            rows = config.rowsPerSeries,
            timespan = config.timespan,
            startOpenTime = config.startOpenTime,
            assetIndex = index,
            seed = config.seed,
        )
        val parsed = feed.parseCachedCsv(key, csv)
        HarnessReplayInput(key, parsed.block)
    }
}

private fun generatedArchiveCsv(
    symbol: String,
    rows: Int,
    timespan: TimeSpan,
    startOpenTime: Long,
    assetIndex: Int,
    seed: Int,
): String {
    require(rows > 0) { "rows must be positive" }
    val random = Random(seed + assetIndex * 65_537)
    val intervalMillis = timespan.seconds * 1_000L
    val basePrices = doubleArrayOf(69_000.0, 3_800.0, 142.0, 580.0, 0.62, 0.54, 98.0, 7.2)
    var price = basePrices[assetIndex % basePrices.size]
    val out = StringBuilder()
    out.append("open_time,open,high,low,close,volume,close_time,quote_asset_volume,number_of_trades,taker_buy_base_volume,taker_buy_quote_volume,ignore\n")
    for (row in 0 until rows) {
        val openTime = startOpenTime + row * intervalMillis
        val open = price
        val drift = 0.00005 * (assetIndex + 1)
        val wave = sin((row + assetIndex * 13).toDouble() / 17.0) * 0.0025 +
            cos((row + assetIndex * 7).toDouble() / 43.0) * 0.0012
        val noise = random.nextDouble(-0.0018, 0.0018)
        val close = max(0.000001, open * (1.0 + drift + wave + noise))
        val high = max(open, close) * (1.0 + random.nextDouble(0.0002, 0.0030))
        val low = min(open, close) * (1.0 - random.nextDouble(0.0002, 0.0030))
        val volume = 100.0 + assetIndex * 19.0 + row % 37 + random.nextDouble(0.0, 25.0)
        val quoteVolume = volume * close
        val trades = 25 + (row + assetIndex * 11) % 95
        val takerBase = volume * random.nextDouble(0.35, 0.65)
        val takerQuote = takerBase * close
        out.append(openTime).append(',')
            .append(open).append(',')
            .append(high).append(',')
            .append(low).append(',')
            .append(close).append(',')
            .append(volume).append(',')
            .append(openTime + intervalMillis - 1).append(',')
            .append(quoteVolume).append(',')
            .append(trades).append(',')
            .append(takerBase).append(',')
            .append(takerQuote).append(",0\n")
        price = close
    }
    return out.toString()
}

private fun Double.short(): String {
    val rounded = kotlin.math.round(this * 100.0) / 100.0
    return rounded.toString()
}
