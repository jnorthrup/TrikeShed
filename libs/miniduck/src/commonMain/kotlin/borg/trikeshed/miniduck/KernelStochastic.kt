package borg.trikeshed.miniduck

import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.at
import borg.trikeshed.lib.*
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
    val name: CharSequence,
    val expectedReturn: Double,
    val volatility: Double,
)

// ── Feature Transformer ──────────────────────────────────────────────────────

/**
 * Transforms a raw [MiniCursor] into a feature-augmented cursor according to
 * the supplied parameter map.
 */
interface KernelFeatureTransformer {
    fun transform(cursor: Cursor, params: Map<CharSequence, Any>): Cursor
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
    suspend fun train(cursor: Cursor, params: Map<CharSequence, Any>): StochasticPolicy
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
    symbols: List<CharSequence>,
    timeframes: List<CharSequence>,
    searchSpace: List<Map<CharSequence, Any>>,
    cacheProvider: (CharSequence, CharSequence) -> Cursor,
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

// ── Concrete implementations ─────────────────────────────────────────────────

/**
 * Example transformer that attaches cached stochastic indicator columns (stoch_k, stoch_d)
 * to each row. Params: symbol, timeframe, kPeriod (default 14), dPeriod (default 3).
 * Falls back to the unmodified cursor if stochastic data is not yet cached.
 */
class ExampleKernelTransformer : KernelFeatureTransformer {
    override fun transform(cursor: Cursor, params: Map<CharSequence, Any>): Cursor {
        val symbol = params["symbol"] as? CharSequence ?: return cursor
        val timeframe = params["timeframe"] as? CharSequence ?: return cursor
        val kPeriod = (params["kPeriod"] as? Number)?.toInt() ?: 14
        val dPeriod = (params["dPeriod"] as? Number)?.toInt() ?: 3

        val stoch = HarnessStochasticCache.get(symbol, timeframe, kPeriod, dPeriod) ?: return cursor

        return cursor.size j { i: Int ->
            val kVal = if (i < stoch.k.size) stoch.k[i] else Double.NaN
            val dVal = if (i < stoch.d.size) stoch.d[i] else Double.NaN
            val src = cursor.at(i)
            val srcKeys = (src as? DocRowVec)?.keys
            val srcCells = (src as? DocRowVec)?.cells
            val mergedKeys = (srcKeys?.let { k -> (0 until k.size).map { k[it] } } ?: emptyList()) + listOf("stoch_k", "stoch_d")
            val mergedCells = (srcCells?.let { c -> (0 until c.size).map { c[it] } } ?: emptyList()) + listOf(kVal, dVal)
            DocRowVec(keys = mergedKeys, cells = mergedCells).toRowVec()
        }
    }
}

/** No-op trainer: immediately returns a trivial policy without computation. */
class NoOpTrainer : KernelStochasticTrainer {
    override val lifecycleState: ElementState = ElementState.OPEN
    override suspend fun open() {}
    override suspend fun train(cursor: Cursor, params: Map<CharSequence, Any>): StochasticPolicy {
        val name = "${params["symbol"] ?: "unknown"}/${params["timeframe"] ?: "??"}"
        return StochasticPolicy(name = name, expectedReturn = 0.0, volatility = 0.0)
    }
    override suspend fun drain() {}
    override suspend fun close() {}
}
