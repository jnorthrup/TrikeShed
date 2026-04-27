package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import borg.trikeshed.context.ElementState as ElementLifecycleState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

// Simple policy result produced by a trainer.
data class StochasticPolicy(val name: String, val expectedReturn: Double, val volatility: Double)

// Minimal KernelStochasticTrainer with explicit lifecycle
interface KernelStochasticTrainer {
    val lifecycleState: ElementLifecycleState

    suspend fun open()
    suspend fun train(cursor: MiniCursor, params: Map<String, Any>): StochasticPolicy?
    suspend fun drain()
    suspend fun close()
}

// No-op trainer used for tests: calculates a trivial expectedReturn as mean of log returns
class NoOpTrainer : KernelStochasticTrainer {
   var state = ElementLifecycleState.CREATED
    override val lifecycleState: ElementLifecycleState get() = state

    override suspend fun open() {
        if (state == ElementLifecycleState.CREATED) state = ElementLifecycleState.OPEN
    }

    override suspend fun train(cursor: MiniCursor, params: Map<String, Any>): StochasticPolicy? = withContext(Dispatchers.Default) {
        // build a Double series for "log_return" column if present
        val logSeries: Series<Double> = cursor.size j { idx ->
            val row = cursor.at(idx) as? DocRowVec
            val v = row?.get("log_return")
            when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: Double.NaN
                is Double -> v
                else -> Double.NaN
            }
        }

        val mean = if (logSeries.size > 0) {
            var s = 0.0
            var c = 0
            for (i in 0 until logSeries.size) {
                val v = logSeries[i]
                if (!v.isNaN()) { s += v; c++ }
            }
            if (c == 0) 0.0 else s / c
        } else 0.0
        StochasticPolicy(name = params.toString(), expectedReturn = mean, volatility = 0.0)
    }

    override suspend fun drain() {
        if (state.isAtLeast(ElementLifecycleState.OPEN) && state.isLessThan(ElementLifecycleState.DRAINING)) {
            state = ElementLifecycleState.DRAINING
            // nothing special
            state = ElementLifecycleState.CLOSED
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementLifecycleState.OPEN) && state.isLessThan(ElementLifecycleState.CLOSED)) {
            state = ElementLifecycleState.CLOSED
        }
    }
}

// Simple harness to execute cartesian fan-out using structured concurrency
suspend fun executeKernelOptimizingHarness(
    symbols: List<String>,
    timeframes: List<String>,
    searchSpace: List<Map<String, Any>>, // list of param maps
    cacheProvider: (String, String) -> MiniCursor,
    transformer: KernelFeatureTransformer,
    trainer: KernelStochasticTrainer
): List<StochasticPolicy> = coroutineScope {
    trainer.open()
    val deferred = symbols.flatMap { sym ->
        timeframes.flatMap { tf ->
            searchSpace.map { params ->
                async {
                    try {
                        val raw = cacheProvider(sym, tf)
                        val enriched = transformer.transform(raw, params)
                        trainer.train(enriched, params)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }
    val result = deferred.awaitAll().filterNotNull()
    trainer.drain()
    trainer.close()
    result
}
