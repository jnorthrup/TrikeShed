package borg.trikeshed.dreamer


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
    public val initialCapital: Double,
    public val mutationStep: Double = 0.01,
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

    public suspend fun evaluate(
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
