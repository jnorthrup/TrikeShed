@file:Suppress("NonAsciiCharacters", "FunctionName", "ObjectPropertyName")

package borg.trikeshed.duck

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.grad.*
import borg.trikeshed.lib.*
import kotlin.test.*
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import kotlin.system.measureNanoTime

/**
 * KotlingradThinSliceTest — real data TDD for the kotlingrad+TrikeShed thin slice.
 *
 * Pattern follows DayJobTest from columnar:
 *   - init{} loads real data from DuckDB
 *   - measureNanoTimeStr for timing
 *   - keyboard aliases shown alongside glyphs
 *
 * NOTE: TradePairIoMux integration tests moved to freqtrade/src/kotlin-engine.
 */
class KotlingradThinSliceTest {

    // -- data loaded once in init{} --

    private val dbPath = Paths.get("/Users/jim/work/freqtrade/user_data/data/candles_coinbase.duckdb")
    private lateinit var cursor: DiffDuckCursor
    private val pair = "BTC/USD"
    private val candleLimit = 50

    // -- SVar parameters --

    private val spanVar = SVar(DReal, "span")
    private val fastVar = SVar(DReal, "fast")
    private val slowVar = SVar(DReal, "slow")
    private val sigVar = SVar(DReal, "sig")
    private val sharpVar = SVar(DReal, "sharp")

    private val bindings = mapOf(
        spanVar to 20.0,
        fastVar to 12.0,
        slowVar to 26.0,
        sigVar to 9.0,
        sharpVar to 1.0,
    )

    init {
        System.err.println("KotlingradThinSliceTest init: loading $pair from $dbPath")

        if (!Files.exists(dbPath)) {
            fail("candle database missing: $dbPath")
        }

        cursor = DiffDuckCursor.open(dbPath.toString(), pair, "5m", candleLimit)
        System.err.println("cursor ready: ${cursor.size} rows, columns=${cursor.columns}")
    }

    private fun measureNanoTimeStr(block: () -> Unit): String =
        Duration.ofNanos(measureNanoTime(block)).toString()

    @Test fun `cursor opens with correct size`() {
        assertEquals(candleLimit, cursor.size)
        assertTrue(cursor.columns.containsAll(listOf("open", "high", "low", "close", "volume")))
        System.err.println("cursor: ${cursor.size} rows")
    }

    @Test fun `emaFold produces symbolic series`() {
        val ema = cursor.emaFold(
            col = "close",
            span = spanVar,
            seedMode = DiffDuckCursor.EmaSeedMode.FIRST_OBSERVED,
            infiniteIndexAdapter = false,
        )
        assertEquals(candleLimit, ema.a)
        val v0 = ema[0].evalDouble(bindings)
        System.err.println("emaFold: ema[0]=$v0")
    }

    @Test fun `emaFold supports infinite index adapter mode`() {
        val emaInfinite = cursor.emaFold(
            col = "close",
            span = spanVar,
            seedMode = DiffDuckCursor.EmaSeedMode.FIRST_OBSERVED,
            infiniteIndexAdapter = true,
        )
        val emaFinite = cursor.emaFold(
            col = "close",
            span = spanVar,
            seedMode = DiffDuckCursor.EmaSeedMode.FIRST_OBSERVED,
            infiniteIndexAdapter = false,
        )
        val firstInfinite = emaInfinite[0].evalDouble(bindings)
        val finiteFirst = emaFinite[0].evalDouble(bindings)
        val finiteLast = emaFinite[candleLimit - 1].evalDouble(bindings)
        val deepNegative = emaInfinite[-1_000_000].evalDouble(bindings)
        val deepPositive = emaInfinite[1_000_000].evalDouble(bindings)
        assertTrue(firstInfinite.isFinite())
        assertEquals(Int.MAX_VALUE, emaInfinite.a)
        assertEquals(candleLimit, emaFinite.a)
        assertEquals(finiteFirst, deepNegative, 1e-10)
        assertEquals(finiteLast, deepPositive, 1e-10)
    }

    @Test fun `emaFold supports explicit zero seed math`() {
        val emaZeroSeed = cursor.emaFold(
            col = "close",
            span = spanVar,
            seedMode = DiffDuckCursor.EmaSeedMode.ZERO,
            infiniteIndexAdapter = false,
        )
        val v0 = emaZeroSeed[0].evalDouble(bindings)
        assertEquals(0.0, v0, 1e-12)
    }

    @Test fun `pancake integrates as kotlingrad series`() {
        val pancake = cursor.pancakeKotlingrad("open", "close")
        assertEquals(candleLimit * 2, pancake.a)
        val p0 = pancake[0].evalDouble(emptyMap())
        assertTrue(p0.isFinite())
        System.err.println("pancakeKotlingrad: p0=$p0 size=${pancake.a}")
    }

    @Test fun `ema diff eval produces gradient of ema wrt span`() {
        val ema = cursor.emaFold(
            col = "close",
            span = spanVar,
            seedMode = DiffDuckCursor.EmaSeedMode.FIRST_OBSERVED,
            infiniteIndexAdapter = false,
        )
        val dEma: Series<SFun<DReal>> = ema `∂` spanVar
        val dEmaVal: Series<Double> = dEma `≈` bindings
        assertEquals(0.0, dEmaVal[0], 1e-10)
    }

    @Test fun `softPnlFold grad eval`() {
        val macd = cursor.macdFold("close", fastVar, slowVar)
        val signal = cursor.emaFold("close", sigVar)
        val buy = cursor.softCrossoverFold(macd, signal, sharpVar)
        val sell = cursor.softCrossoverFold(signal, macd, sharpVar)
        val pnl = cursor.softPnlFold("close", buy, sell)

        val pnlVal = pnl `≈` bindings
        val gradVec = pnl.`∇`(fastVar, slowVar, sigVar, sharpVar)
        val gradVals = gradVec `≈` bindings
        assertEquals(4, gradVals.a)
        System.err.println("softPnl: value=$pnlVal, grads=${gradVals[0]}, ${gradVals[1]}, ${gradVals[2]}, ${gradVals[3]}")
    }
}
