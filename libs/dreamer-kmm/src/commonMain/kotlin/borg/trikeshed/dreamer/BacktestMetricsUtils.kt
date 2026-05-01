package borg.trikeshed.dreamer

public fun HarnessRunResult.fitness(initialCapital: Double, genome: Genome): Double {
    val totalReturn = if (initialCapital > 0.0) (finalTotalValue - initialCapital) / initialCapital else 0.0
    val tradeScore = cycles.count { it.result.anyTradesThisCycle }.toDouble()
    val drawdown = maxDrawdown(initialCapital)
    val penalty = genome.getDouble("FITNESS_DRAWDOWN_PENALTY", 1.0)
    return totalReturn + (tradeScore * 0.001) - (drawdown * penalty)
}

public fun HarnessRunResult.maxDrawdown(initialCapital: Double): Double {
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
