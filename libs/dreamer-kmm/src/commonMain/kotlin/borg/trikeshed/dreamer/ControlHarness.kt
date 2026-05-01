package borg.trikeshed.dreamer

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.at
import borg.trikeshed.cursor.cellsToRowVec
import borg.trikeshed.cursor.doubleValue
import borg.trikeshed.cursor.longValue
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

typealias ControlFrameRoute = Join<KlineSeriesKey, Int>

data class ControlPairFrame(
    val route: ControlFrameRoute,
    val walletFree: Cursor,
    val horizonOhlcv: Cursor,
    val pancake: Cursor,
    val ochl: Cursor,
)

data class ControlHarnessFrame(
    val tick: Int,
    val openTime: Long,
    val pairs: List<ControlPairFrame>,
) {
    fun materializePancake(): DoubleArray {
        val cells = mutableListOf<Double>()
        pairs.forEach { pair ->
            pair.walletFree.rowDoublesInto(cells)
            pair.pancake.rowDoublesInto(cells)
        }
        return DoubleArray(cells.size) { index -> cells[index] }
    }
}

enum class SimulationDepthMode(val maxTicks: Int) {
    SHORT(15_000),
    MEDIUM(45_000),
    LONG(Int.MAX_VALUE),
}

enum class SimulationSweepMode {
    GRID,
    MICRO_GRID,
    FINE_TUNE,
}

data class ControlSimulationOptions(
    val initialCapital: Double = 0.0,
    val depthMode: SimulationDepthMode = SimulationDepthMode.MEDIUM,
    val sweepMode: SimulationSweepMode = SimulationSweepMode.GRID,
    val shadowFeeRate: Double = 0.01,
    val minTradesForPromotion: Int = 1,
    val fitnessDrawdownPenalty: Double = 1.0,
) {
    init {
        require(initialCapital >= 0.0) { "initialCapital must be non-negative" }
        require(shadowFeeRate >= 0.0) { "shadowFeeRate must be non-negative" }
        require(minTradesForPromotion >= 0) { "minTradesForPromotion must be non-negative" }
        require(fitnessDrawdownPenalty >= 0.0) { "fitnessDrawdownPenalty must be non-negative" }
    }
}

class ControlSimulation(
    val inputs: List<HarnessReplayInput>,
    val options: ControlSimulationOptions = ControlSimulationOptions(),
    val valueSymbol: String = "USDT",
    val wallet: SimWallet = SimWallet(),
) {
    var simTimeIndex: Int = 0
        public set

    val tradedPairs: List<TradingPair> = inputs.map { input -> input.key.a }
    val simTimeLimit: Int = minOf(inputs.minOfOrNull { input -> input.block.rowCount } ?: 0, options.depthMode.maxTicks)

    init {
        require(inputs.isNotEmpty()) { "ControlSimulation requires at least one kline block" }
        inputs.forEach { input ->
            check(input.block.state == KlineBlock.State.SEALED) {
                "KlineBlock for ${input.key.symbol} must be sealed"
            }
        }
        if (options.initialCapital != 0.0) wallet.record(valueSymbol, options.initialCapital)
    }

    fun advance() {
        if (simTimeIndex < simTimeLimit) simTimeIndex += 1
    }
}

class ControlHarness(
    public val horizonDepth: Int,
    public val valueSymbol: String = "USDT",
    public val subscribers: List<AsyncContextElement> = emptyList(),
) : AsyncContextElement() {
    companion object Key : AsyncContextKey<ControlHarness>()

    init {
        require(horizonDepth > 0) { "horizonDepth must be positive" }
    }

    override val key: AsyncContextKey<ControlHarness> get() = Key
    override val fanoutSubscribers: List<AsyncContextElement> get() = subscribers

    suspend fun frame(simulation: ControlSimulation): ControlHarnessFrame =
        frameFromBlocks(simulation.inputs, simulation.wallet, simulation.simTimeIndex)

    suspend fun nextFrame(simulation: ControlSimulation): ControlHarnessFrame {
        val frame = frame(simulation)
        simulation.advance()
        return frame
    }

    suspend fun frameFromBlocks(
        inputs: List<HarnessReplayInput>,
        wallet: SimWallet,
        tick: Int,
    ): ControlHarnessFrame {
        require(inputs.isNotEmpty()) { "ControlHarness requires at least one kline block" }
        val sources = inputs.map { input ->
            check(input.block.state == KlineBlock.State.SEALED) {
                "KlineBlock for ${input.key.symbol} must be sealed"
            }
            KlineSeriesSource(input.key, input.block.asCursor())
        }
        return frameFromSources(sources, wallet, tick)
    }

    suspend fun frameFromSources(
        sources: List<KlineSeriesSource>,
        wallet: SimWallet,
        tick: Int,
    ): ControlHarnessFrame = coroutineScope {
        check(lifecycleState == ElementState.OPEN || lifecycleState == ElementState.ACTIVE) {
            "ControlHarness must be open before projecting frames"
        }
        require(sources.isNotEmpty()) { "ControlHarness requires at least one source" }
        require(tick >= 0) { "tick must be non-negative" }
        sources.forEach { source ->
            require(source.cursor.size > 0) { "Source ${source.key.symbol} has no rows" }
        }

        state = ElementState.ACTIVE
        val universe = sources.map { source -> source.key.a }
        val pairs = sources.map { source ->
            async { pairFrame(source, universe, wallet, tick) }
        }.awaitAll()

        ControlHarnessFrame(
            tick = tick,
            openTime = pairs.minOfOrNull { pair -> pair.ochl.at(0).longValue("openTime") } ?: 0L,
            pairs = pairs,
        )
    }

    public fun pairFrame(
        source: KlineSeriesSource,
        universe: List<TradingPair>,
        wallet: SimWallet,
        tick: Int,
    ): ControlPairFrame {
        val rowLimit = (tick + 1).coerceIn(1, source.cursor.size)
        val currentIndex = rowLimit - 1
        val horizon = source.cursor.horizonOhlcv(rowLimit, horizonDepth)
        return ControlPairFrame(
            route = source.key j currentIndex,
            walletFree = wallet.walletFree(source.key.a, universe, valueSymbol),
            horizonOhlcv = horizon,
            pancake = horizon.pancake(),
            ochl = source.cursor.ochlAt(currentIndex),
        )
    }
}

fun SimWallet.walletFree(
    pair: TradingPair,
    universe: List<TradingPair>,
    valueSymbol: String = pair.b,
): Cursor {
    val keys = (universe.flatMap { tradePair -> listOf(tradePair.a, tradePair.b) } + pair.a + pair.b + valueSymbol)
        .distinct()
        .sorted()
    val cells = keys.map { key ->
        when (key) {
            pair.a -> freeBalance(pair.a)
            pair.b -> -freeBalance(pair.b)
            valueSymbol -> 1e-10
            else -> -1e-10
        }
    }
    return 1 j { _: Int -> cellsToRowVec(cells.toSeries(), keys.toSeries()) }
}

fun horizonIndex(index: Int, viewPoints: Int, datapoints: Int): Int {
    require(index >= 0) { "index must be non-negative" }
    require(viewPoints > 0) { "viewPoints must be positive" }
    if (datapoints <= 1) return 0
    val dpDouble = datapoints.toDouble()
    val vpDouble = viewPoints.toDouble()
    val compressed = (dpDouble - 1.0 - sin((vpDouble - index) / vpDouble * (PI / 2.0)) * dpDouble - 1.0).toInt()
    return max(index.coerceAtMost(datapoints - 1), compressed).coerceIn(0, datapoints - 1)
}
public fun Cursor.horizonOhlcv(rowLimit: Int, depth: Int): Cursor {
    val source = this
    return depth j { depthIndex: Int ->
        val row = source.at(horizonIndex(depthIndex, depth, rowLimit))
        cellsToRowVec(
            cells = listOf(
                row.doubleValue("open"),
                row.doubleValue("high"),
                row.doubleValue("low"),
                row.doubleValue("close"),
                row.doubleValue("volume"),
            ).toSeries(),
            keys = listOf("open", "high", "low", "close", "volume").toSeries(),
        )
    }
}
public fun Cursor.pancake(): Cursor {
    val source = this
    return 1 j { _: Int ->
        val keys = mutableListOf<String>()
        val cells = mutableListOf<Any?>()
        for (rowIndex in 0 until source.size) {
            val row = source.at(rowIndex)
            for (cellIndex in 0 until row.size) {
                val cell = row.b(cellIndex)
                keys += "${cell.b().a}/$rowIndex"
                cells += cell.a
            }
        }
        cellsToRowVec(cells.toSeries(), keys.toSeries())
    }
}
public fun Cursor.ochlAt(index: Int): Cursor {
    val row = at(index)
    return 1 j { _: Int ->
        cellsToRowVec(
            cells = listOf(
                row.longValue("openTime"),
                row.doubleValue("open"),
                row.doubleValue("close"),
                row.doubleValue("high"),
                row.doubleValue("low"),
            ).toSeries(),
            keys = listOf("openTime", "open/0", "close/0", "high/0", "low/0").toSeries(),
        )
    }
}
public fun Cursor.rowDoublesInto(out: MutableList<Double>) {
    val row = at(0)
    for (index in 0 until row.size) {
        val value = row.b(index).a
        out += when (value) {
            is Number -> value.toDouble()
            is String -> value.toDouble()
            else -> error("Pancake materialization requires numeric cells, got $value")
        }
    }
}
