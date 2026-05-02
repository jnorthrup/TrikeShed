package borg.trikeshed.miniduck

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

// ── Stochastic Policy ────────────────────────────────────────────────────────

/**
 * Represents a stochastic optimization policy discovered by the kernel harness.
 *
 * @param name            human-readable label (e.g. dominant regime name)
 * @param expectedReturn  expected return metric produced by the trainer
 * @param volatility      volatility threshold or measure from the trainer
 */
data class StochasticPolicy(
    val name: String,
    val expectedReturn: Double,
    val volatility: Double,
)

// ── Feature Transformer ──────────────────────────────────────────────────────

/**
 * Transforms a raw [MiniCursor] into a feature-augmented cursor according to
 * the supplied parameter map.
 */
interface KernelFeatureTransformer {
    fun transform(cursor: MiniCursor, params: Map<String, Any>): MiniCursor
}

// ── Stochastic Trainer ───────────────────────────────────────────────────────

/**
 * Lifecycle-managed trainer that consumes a (possibly transformed) cursor and
 * produces a [StochasticPolicy].
 *
 * Typical lifecycle: open → (train)+ → drain → close
 */
interface KernelStochasticTrainer {
    val lifecycleState: ElementState

    suspend fun open()
    suspend fun train(cursor: MiniCursor, params: Map<String, Any>): StochasticPolicy
    suspend fun drain()
    suspend fun close()
}

// ── Optimizing Harness ───────────────────────────────────────────────────────

/**
 * Executes the kernel optimizing harness over the full cartesian product of
 * [symbols] × [timeframes] × [searchSpace] using structured concurrency.
 *
 * For each combination the harness:
 *  1. Retrieves a cursor via [cacheProvider]
 *  2. Transforms it through [transformer]
 *  3. Trains via [trainer] to produce a [StochasticPolicy]
 *
 * The trainer is opened before and drained+closed after all work completes.
 */
suspend fun executeKernelOptimizingHarness(
    symbols: List<String>,
    timeframes: List<String>,
    searchSpace: List<Map<String, Any>>,
    cacheProvider: (String, String) -> MiniCursor,
    transformer: KernelFeatureTransformer,
    trainer: KernelStochasticTrainer,
): List<StochasticPolicy> {
    trainer.open()
    try {
        return coroutineScope {
            val deferred = symbols.flatMap { symbol ->
                timeframes.flatMap { timeframe ->
                    searchSpace.map { params ->
                        async {
                            val raw = cacheProvider(symbol, timeframe)
                            val features = transformer.transform(raw, params)
                            trainer.train(features, params + ("symbol" to symbol) + ("timeframe" to timeframe))
                        }
                    }
                }
            }
            deferred.map { it.await() }
        }
    } finally {
        trainer.drain()
        trainer.close()
    }
}
