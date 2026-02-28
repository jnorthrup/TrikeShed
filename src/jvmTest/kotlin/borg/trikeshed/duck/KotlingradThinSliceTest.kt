@file:Suppress("NonAsciiCharacters", "FunctionName", "ObjectPropertyName")

package borg.trikeshed.duck

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.grad.*
import borg.trikeshed.lib.*
import kotlin.math.abs
import kotlin.test.*

/**
 * KotlingradThinSliceTest — DayJobTest-style TDD for the kotlingrad+TrikeShed thin slice.
 *
 * Pattern follows DayJobTest from columnar:
 *   - init{} loads real data
 *   - measureNanoTimeStr for timing
 *   - backtick method names
 *   - System.err.println for progress
 *   - keyboard aliases shown alongside glyphs
 *
 * Every glyph operator is tested with both Unicode and keyboard alias forms.
 */
class KotlingradThinSliceTest {

    // -- test data: synthetic OHLCV series (no DuckDB file needed) --

    private val n = 100
    private val close: Series<Double> = n j { i: Int -> 100.0 + i * 0.5 + kotlin.math.sin(i * 0.3) * 5.0 }
    private val open: Series<Double> = n j { i: Int -> close[i] - 0.5 }
    private val high: Series<Double> = n j { i: Int -> close[i] + 2.0 }
    private val low: Series<Double> = n j { i: Int -> close[i] - 2.0 }
    private val volume: Series<Double> = n j { i: Int -> 1000.0 + i * 10.0 }

    private val cursor = DiffDuckCursor.fromColumns(mapOf(
        "open" to open,
        "high" to high,
        "low" to low,
        "close" to close,
        "volume" to volume,
    ))

    // -- SVar parameters --

    private val spanVar = SVar(DReal, "span")
    private val fastVar = SVar(DReal, "fast")
    private val slowVar = SVar(DReal, "slow")
    private val sigVar = SVar(DReal, "sig")
    private val sharpVar = SVar(DReal, "sharp")

    // ── cursor + open ────────────────────────────────────────────────────

    @Test fun `cursor opens with correct size and columns`() {
        assertEquals(n, cursor.size)
        assertTrue(cursor.columns.containsAll(listOf("open", "high", "low", "close", "volume")))
        assertEquals(close[0], cursor["close"][0])
        System.err.println("cursor: ${cursor.size} rows, ${cursor.columns.size} cols")
    }

    // ── close + lift (↑ / lift) ──────────────────────────────────────────

    @Test fun `close lift to SFun`() {
        // glyph
        val lifted: Series<SFun<DReal>> = cursor["close"].`↑`
        // keyboard alias
        val lifted2: Series<SFun<DReal>> = cursor["close"].lift
        assertEquals(n, lifted.a)
        assertEquals(n, lifted2.a)
        // lifted constant evaluates to the raw value
        val v = lifted[0].evalDouble(emptyMap())
        assertEquals(close[0], v, 1e-10)
        System.err.println("lift: close[0]=${close[0]} -> lifted=${v}")
    }

    // ── emaFold ──────────────────────────────────────────────────────────

    @Test fun `emaFold produces symbolic series`() {
        val ema = cursor.emaFold("close", spanVar)
        assertEquals(n, ema.a)
        // evaluate at span=20
        val b = mapOf(spanVar to 20.0 as Number)
        val v0 = ema[0].evalDouble(b)
        assertEquals(close[0], v0, 1e-10, "ema[0] should equal close[0]")
        // later values should be smoothed
        val vEnd = ema[n - 1].evalDouble(b)
        assertTrue(vEnd > 100.0, "ema end should track uptrend")
        System.err.println("emaFold: ema[0]=$v0, ema[${n - 1}]=$vEnd")
    }

    // ── ema + diff/∂ + eval/≈ ────────────────────────────────────────────

    @Test fun `ema diff eval produces gradient of ema wrt span`() {
        val ema = cursor.emaFold("close", spanVar)
        val b = mapOf(spanVar to 20.0)

        // glyph form
        val dEma: Series<SFun<DReal>> = ema `∂` spanVar
        val dEmaVal: Series<Double> = dEma `≈` b

        // keyboard alias form
        val dEma2: Series<SFun<DReal>> = ema diff spanVar
        val dEmaVal2: Series<Double> = dEma2 eval b

        assertEquals(n, dEmaVal.a)
        // derivative at index 0 should be 0 (constant seed)
        assertEquals(0.0, dEmaVal[0], 1e-10)
        // later derivatives should be nonzero
        assertTrue(dEmaVal[n - 1] != 0.0, "dEma/dSpan at end should be nonzero")
        // both forms agree
        assertEquals(dEmaVal[n - 1], dEmaVal2[n - 1], 1e-10)
        System.err.println("ema diff: dEma[0]=${dEmaVal[0]}, dEma[${n - 1}]=${dEmaVal[n - 1]}")
    }

    // ── macdFold + diff/∂ + eval/≈ ───────────────────────────────────────

    @Test fun `macdFold diff eval`() {
        val macd = cursor.macdFold("close", fastVar, slowVar)
        val b = mapOf(fastVar to 12.0, slowVar to 26.0)
        val macdVal = macd[n - 1].evalDouble(b.mapValues { it.value as Number })
        // MACD = ema(fast) - ema(slow); in uptrend fast > slow -> positive
        assertTrue(macdVal > 0.0, "MACD should be positive in uptrend")

        // gradient wrt fast
        val dFast = (macd `∂` fastVar) `≈` b
        assertTrue(dFast[n - 1] != 0.0, "dMACD/dFast should be nonzero")

        System.err.println("macdFold: macd[end]=$macdVal, dMACD/dFast=${dFast[n - 1]}")
    }

    // ── softPnlFold + grad/∇ + eval/≈ ───────────────────────────────────

    @Test fun `softPnlFold grad eval`() {
        val macd = cursor.macdFold("close", fastVar, slowVar)
        val signal = cursor.emaFold("close", sigVar)
        val buy = cursor.softCrossoverFold(macd, signal, sharpVar)
        val sell = cursor.softCrossoverFold(signal, macd, sharpVar)
        val pnl = cursor.softPnlFold("close", buy, sell)

        val b = mapOf(fastVar to 12.0, slowVar to 26.0, sigVar to 9.0, sharpVar to 1.0)
        val pnlVal = pnl `≈` b
        System.err.println("softPnl: value=$pnlVal")

        // gradient via glyph
        val gradVec: Series<SFun<DReal>> = pnl.`∇`(fastVar, slowVar, sigVar, sharpVar)
        val gradVals: Series<Double> = gradVec `≈` b
        assertEquals(4, gradVals.a)

        // keyboard alias
        val gradVec2 = pnl.grad(fastVar, slowVar, sigVar, sharpVar)
        val gradVals2 = gradVec2 eval b
        assertEquals(gradVals[0], gradVals2[0], 1e-10)

        System.err.println("  grads: fast=${gradVals[0]}, slow=${gradVals[1]}, sig=${gradVals[2]}, sharp=${gradVals[3]}")
    }

    // ── hadamard/ʘ + dot double-trouble ──────────────────────────────────

    @Test fun `hadamard and dot double-trouble`() {
        val a = 5 j { i: Int -> (i + 1).toDouble() }  // [1, 2, 3, 4, 5]
        val b = 5 j { i: Int -> (i + 1).toDouble() * 2.0 }  // [2, 4, 6, 8, 10]

        // concrete Double path — glyph
        val hw: Series<Double> = a `ʘ` b
        assertEquals(2.0, hw[0], 1e-10)
        assertEquals(50.0, hw[4], 1e-10)

        // keyboard alias
        val hw2 = a hadamard b
        assertEquals(hw[0], hw2[0], 1e-10)

        val d: Double = a dot b  // 1*2 + 2*4 + 3*6 + 4*8 + 5*10 = 110
        assertEquals(110.0, d, 1e-10)

        // symbolic SFun path
        val x = SVar(DReal, "x")
        val sa: Series<SFun<DReal>> = a.`↑` α { it * x }
        val sb: Series<SFun<DReal>> = b.`↑`
        val shw: Series<SFun<DReal>> = sa `ʘ` sb
        val sDot: SFun<DReal> = sa dot sb

        val bindings = mapOf(x to 1.0)
        assertEquals(2.0, shw[0] `≈` bindings, 1e-10)
        assertEquals(110.0, sDot `≈` bindings, 1e-10)

        // differentiate the dot product wrt x — should give sum(a[i]*b[i]) = 110
        val dDot = sDot diff x eval bindings
        assertEquals(110.0, dDot, 1e-10)

        System.err.println("hadamard[0]=${hw[0]}, dot=$d, symbolic dot=$dDot")
    }

    // ── pancake + tangent/⊗ + diff/∂ ─────────────────────────────────────

    @Test fun `pancake tangent diff`() {
        val pancake = cursor.pancake("open", "high", "low", "close")
        assertEquals(n * 4, pancake.a, "pancake size = rows * cols")

        // tangent bundle: value series ⊗ gradient series
        val ema = cursor.emaFold("close", spanVar)
        val dEma = ema `∂` spanVar

        // only take close column size for tangent (both have size n)
        val closeSlice = cursor["close"]
        val tb: Series<Join<Double, SFun<DReal>>> = closeSlice `⊗` dEma

        // keyboard alias
        val tb2 = closeSlice tangent dEma

        assertEquals(n, tb.a)
        val (val0, grad0) = tb[0]
        assertEquals(close[0], val0, 1e-10)

        val (val02, _) = tb2[0]
        assertEquals(val0, val02, 1e-10)

        val grad0val = grad0.evalDouble(mapOf(spanVar to 20.0 as Number))
        System.err.println("pancake: ${pancake.a} flat elements")
        System.err.println("tangent[0]: value=$val0, grad=$grad0val")
    }

    // ── expression isomorphism / ≅ / iso ─────────────────────────────────

    @Test fun `emaFold iso cache dedup`() {
        val a = cursor.emaFold("close", spanVar)
        val b = cursor.emaFold("close", spanVar)  // same call, should cache-hit

        // same reference from expression cache
        assertTrue(a === b, "emaFold should return cached reference for same key")

        // structural isomorphism — glyph
        assertTrue(a[0] `≅` b[0])

        // keyboard alias
        assertTrue(a[0] iso b[0])

        // different param -> different expression
        val otherVar = SVar(DReal, "other")
        val c = cursor.emaFold("close", otherVar)
        assertFalse(a[1] `≅` c[1])

        System.err.println("iso: same-key cached=true, different-key iso=false")
    }

    // ── CursorBridge + windup ────────────────────────────────────────────

    @Test fun `windup creates 24 bridges`() {
        val mux = TradePairIoMux.windupFromCursor("BTC/USD", cursor, numAgents = 24)
        assertEquals(24, mux.numAgents)
        assertEquals("BTC/USD", mux.pair)
        assertTrue(mux.sharedPancake.a > 0)

        // each bridge has unique SVar names
        val names = (0 until 24).map { mux.bridges[it].macdFast.name }.toSet()
        assertEquals(24, names.size, "each agent should have unique SVar name")

        System.err.println("windup: ${mux.numAgents} agents, pancake=${mux.sharedPancake.a} elements")
    }

    // ── CursorBridge.step ────────────────────────────────────────────────

    @Test fun `bridge step produces gradient result`() {
        val bridge = CursorBridge(
            agentId   = 0,
            macdFast  = SVar(DReal, "fast_0"),
            macdSlow  = SVar(DReal, "slow_0"),
            sigSpan   = SVar(DReal, "sig_0"),
            sharpness = SVar(DReal, "sharp_0"),
        )

        val result = bridge.step(cursor)
        assertEquals(0, result.agentId)
        assertTrue(result.grads.containsKey("fast"))
        assertTrue(result.grads.containsKey("slow"))
        assertTrue(result.grads.containsKey("sig"))
        assertTrue(result.grads.containsKey("sharp"))

        // P&L should be a finite number
        assertTrue(result.pnl.isFinite(), "pnl should be finite")

        // All gradients should be finite (may be very small with saturated sigmoids)
        result.grads.forEach { (name, grad) ->
            assertTrue(grad.isFinite(), "grad[$name] should be finite, got $grad")
        }

        System.err.println("bridge step: pnl=${result.pnl}, grads=${result.grads}")
    }

    // ── _d literal constructor ───────────────────────────────────────────

    @Test fun `_d literal diff eval`() {
        val constants = _d[100.0, 101.0, 102.0]
        assertEquals(3, constants.a)

        val x = SVar(DReal, "x")
        // scale by param
        val scaled: Series<SFun<DReal>> = constants.a j { i: Int -> constants[i] * x }

        // differentiate wrt x
        val dScaled = scaled diff x
        val vals = dScaled eval mapOf(x to 1.0)

        // d(100*x)/dx = 100, d(101*x)/dx = 101, etc.
        assertEquals(100.0, vals[0], 1e-10)
        assertEquals(101.0, vals[1], 1e-10)
        assertEquals(102.0, vals[2], 1e-10)

        System.err.println("_d literal: d(100*x)/dx=${vals[0]}, d(101*x)/dx=${vals[1]}")
    }
}
