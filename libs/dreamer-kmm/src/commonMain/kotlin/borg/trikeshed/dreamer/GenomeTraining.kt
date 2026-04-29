package borg.trikeshed.dreamer

import borg.trikeshed.couch.kline.KlineBlock

data class GenomeTrainingCandidate(
    val genome: Genome,
    val result: HarnessRunResult,
    val fitness: Double,
)

data class GenomeTrainingResult(
    val champion: Genome,
    val evaluations: List<GenomeTrainingCandidate>,
)

class GenomeTrainer(
    private val initialCapital: Double,
    private val mutationStep: Double = 0.01,
) {
    suspend fun trainOneDimensional(
        key: KlineSeriesKey,
        block: KlineBlock,
        seed: Genome = defaultGenome(),
    ): GenomeTrainingResult {
        val candidates = listOf(
            seed,
            mutateGenome(seed, mapOf("HARVEST_TAKE_PERCENT" to mutationStep)),
            mutateGenome(seed, mapOf("HARVEST_TAKE_PERCENT" to -mutationStep)),
            mutateGenome(seed, mapOf("FLAT_REBALANCE_TRIGGER_PERCENT" to mutationStep)),
        )
        return evaluate(
            candidates = candidates,
            inputs = listOf(HarnessReplayInput(key, block)),
        )
    }

    suspend fun trainPairBag(
        inputs: List<HarnessReplayInput>,
        seed: Genome = defaultGenome(),
    ): GenomeTrainingResult {
        val candidates = listOf(
            seed,
            mutateGenome(seed, mapOf("HARVEST_TAKE_PERCENT" to mutationStep)),
            mutateGenome(seed, mapOf("MIN_SURPLUS_FOR_HARVEST" to mutationStep)),
            mutateGenome(seed, mapOf("FITNESS_DRAWDOWN_PENALTY" to mutationStep)),
        )
        return evaluate(candidates, inputs)
    }

    private suspend fun evaluate(
        candidates: List<Genome>,
        inputs: List<HarnessReplayInput>,
    ): GenomeTrainingResult {
        val evaluations = candidates.map { genome ->
            val run = RealtimeHarness(
                genome = genome,
                initialCapital = initialCapital,
                mode = Mode.SHADOW,
            ).replay(inputs)
            GenomeTrainingCandidate(
                genome = genome,
                result = run,
                fitness = run.fitness(initialCapital, genome),
            )
        }.sortedByDescending { it.fitness }

        return GenomeTrainingResult(
            champion = evaluations.firstOrNull()?.genome ?: candidates.first(),
            evaluations = evaluations,
        )
    }
}

private fun HarnessRunResult.fitness(initialCapital: Double, genome: Genome): Double {
    val totalReturn = if (initialCapital > 0.0) (finalTotalValue - initialCapital) / initialCapital else 0.0
    val tradeScore = cycles.count { it.result.anyTradesThisCycle }.toDouble()
    val drawdown = maxDrawdown(initialCapital)
    val penalty = genome.getDouble("FITNESS_DRAWDOWN_PENALTY", 1.0)
    return totalReturn + (tradeScore * 0.001) - (drawdown * penalty)
}

private fun HarnessRunResult.maxDrawdown(initialCapital: Double): Double {
    var peak = initialCapital
    var maxDrawdown = 0.0
    cycles.forEach { cycle ->
        if (cycle.totalValue > peak) {
            peak = cycle.totalValue
        } else if (peak > 0.0) {
            val dd = (peak - cycle.totalValue) / peak
            if (dd > maxDrawdown) maxDrawdown = dd
        }
    }
    return maxDrawdown
}
