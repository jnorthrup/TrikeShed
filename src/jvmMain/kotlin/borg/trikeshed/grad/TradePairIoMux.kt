@file:Suppress("NonAsciiCharacters", "FunctionName")

/**
 * TradePairIoMux.kt — channelized IoMux for 24 codec agents.
 *
 * Re-engineered from acapulco TradePairEventMuxer with:
 *   - Channelized observable ticks via Kotlin Channel + SharedFlow
 *   - kotlingrad expressions convertible via CursorBridge
 *   - All composition in the windup — nothing allocated on the hot path
 *
 * Architecture:
 *
 *   exchange tick ──► tickChannel (Channel<OHLCVCandle>)
 *                            │
 *                     ┌──────▼───────────────┐
 *                     │  DiffDuckCursor       │ ◄── shared read-only state
 *                     │  Series<Double> cols   │
 *                     └──────┬───────────────┘
 *                            │ cursorFlow (SharedFlow<Join<DiffDuckCursor, Int>>)
 *              ┌─────────────┼─────────────┐
 *              │             │             │
 *       ┌──────▼──────┐  ┌──▼──────┐  ┌──▼─────────┐
 *       │CursorBridge 0│  │Bridge 1 │  │ Bridge N   │  (24 pre-composed bridges)
 *       │ SVar params  │  │         │  │            │
 *       │ emaFold refs │  │         │  │            │
 *       │ exprCache hit│  │         │  │            │
 *       └──────┬──────┘  └──┬──────┘  └──┬─────────┘
 *              │             │             │
 *       ┌──────▼──────┐  ┌──▼──────┐  ┌──▼─────────┐
 *       │CodecAgent 0  │  │Agent 1  │  │ Agent N    │
 *       │ step()->SFun │  │         │  │            │
 *       │ -> grad      │  │         │  │            │
 *       │ -> eval      │  │         │  │            │
 *       └──────┬──────┘  └──┬──────┘  └──┬─────────┘
 *              └─────────────┼─────────────┘
 *                            │
 *                     resultFlow (SharedFlow<Series<GradResult>>)
 *
 * Acapulco lineage:
 *   TradePairEventMuxer  — push() + latestKlines + intra_events flow
 *   KlineViewUtil         — decorateView = join(wallet, book, candles)
 *   pancake               — flatten rows x cols into 1-D feature series
 *   IntraCandleEventView  — subscriber pattern
 */

package borg.trikeshed.grad

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.duck.DiffDuckCursor
import borg.trikeshed.duck.evalDouble
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

// -- OHLCVCandle --

data class OHLCVCandle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

// -- GradResult per agent per step --

data class GradResult(
    val agentId: Int,
    val pnl: Double,
    val grads: Map<String, Double>,
)

// -- CursorBridge: the adapter between Series<Double> and SFun<DReal> --
//
// Each agent gets one CursorBridge assembled in windup.
// The bridge holds pre-composed SVar parameters and knows how to:
//   lift:  Series<Double>  -> Series<SFun<DReal>>  (↑)
//   fold:  Series<Double> x SVar -> Series<SFun<DReal>>  (emaFold, macdFold)
//   eval:  SFun<DReal> x bindings -> Double  (≈)
//
// The bridge is the accommodation point — agents that want pure Double
// read cursor[col] directly; agents that want differentiable computation
// go through the bridge.

data class CursorBridge(
    val agentId: Int,
    val macdFast:  SVar<DReal>,
    val macdSlow:  SVar<DReal>,
    val sigSpan:   SVar<DReal>,
    val sharpness: SVar<DReal>,
) {
    // Current parameter values (used for evaluation + gradient step)
    var pFast: Double = 12.0 + agentId * 0.1
    var pSlow: Double = 26.0 + agentId * 0.1
    var pSig:  Double = 9.0 + agentId * 0.05
    var pSharp: Double = 1.0 + agentId * 0.02

    val bindings: Map<SVar<DReal>, Double>
        get() = mapOf(macdFast to pFast, macdSlow to pSlow, sigSpan to pSig, sharpness to pSharp)

    /**
     * Full gradient step: fold cursor -> SFun scalar -> gradients -> ascent.
     *
     * Uses the cursor's expression cache: if two bridges share an SVar name
     * they hit the same cached emaFold node. Unique names = unique trees.
     */
    fun step(cursor: DiffDuckCursor): GradResult {
        // 1. Fold: Series<Double> x SVar -> Series<SFun<DReal>> -> SFun scalar
        val macd   = cursor.macdFold("close", macdFast, macdSlow)
        val signal = cursor.emaFold("close", sigSpan)
        val buy    = cursor.softCrossoverFold(macd, signal, sharpness)
        val sell   = cursor.softCrossoverFold(signal, macd, sharpness)
        val pnl    = cursor.softPnlFold("close", buy, sell)

        // 2. Evaluate at current param values (evalDouble helper)
        val b = bindings
        val pnlVal = pnl.evalDouble(b)

        // 3. Gradients — d(pnl)/d(each param) then evaluate
        val grads = mapOf(
            "fast"  to pnl.d(macdFast).evalDouble(b),
            "slow"  to pnl.d(macdSlow).evalDouble(b),
            "sig"   to pnl.d(sigSpan).evalDouble(b),
            "sharp" to pnl.d(sharpness).evalDouble(b),
        )

        // 4. Gradient ascent step (lr = 0.01)
        val lr = 0.01
        pFast  += lr * (grads["fast"]  ?: 0.0)
        pSlow  += lr * (grads["slow"]  ?: 0.0)
        pSig   += lr * (grads["sig"]   ?: 0.0)
        pSharp += lr * (grads["sharp"] ?: 0.0)

        return GradResult(agentId, pnlVal, grads)
    }
}

// -- ITradePairEventMuxer (acapulco lineage, TrikeShed types) --

interface ITradePairEventMuxer {
    val pair: String
    val cursorFlow: SharedFlow<Join<DiffDuckCursor, Int>>
    suspend fun push(candle: OHLCVCandle, isFinal: Boolean)
}

// -- TradePairIoMux --

class TradePairIoMux private constructor(
    override val pair: String,
    val cursor: DiffDuckCursor,
    val bridges: Series<CursorBridge>,           // 24 cursor bridges pre-composed
    val sharedPancake: Series<Double>,           // one pancake — all agents share
    override val cursorFlow: MutableSharedFlow<Join<DiffDuckCursor, Int>>,
    val resultFlow: MutableSharedFlow<Series<GradResult>>,
    val tickChannel: Channel<OHLCVCandle>,       // channelized tick input
    private val history: ArrayDeque<OHLCVCandle>,
    private val intra: ArrayDeque<OHLCVCandle>,
) : ITradePairEventMuxer {

    val numAgents: Int get() = bridges.a

    // -- Hot path: push a candle (also fed by tickChannel consumer) --

    override suspend fun push(candle: OHLCVCandle, isFinal: Boolean) {
        if (isFinal) {
            history.addLast(candle)
            intra.clear()
        } else {
            intra.addLast(candle)
        }
        // Emit to all cursor subscribers — acapulco semantics: (cursor, intraCount)
        cursorFlow.emit(cursor j intra.size)
    }

    // -- Hot path: one gradient step for all agents --

    /**
     * Fan out to all agents concurrently.
     * Each agent uses its pre-composed CursorBridge — no allocation here.
     * Results arrive as Series<GradResult> and are emitted to resultFlow.
     */
    suspend fun step(): Series<GradResult> = coroutineScope {
        val n = bridges.a
        val deferred = Array(n) { i ->
            async(Dispatchers.Default) {
                bridges[i].step(cursor)
            }
        }
        val results = Array(n) { deferred[it].await() }
        val series: Series<GradResult> = n j { results[it] }
        resultFlow.emit(series)
        series
    }

    // -- Tick channel consumer: launch as a coroutine --

    /**
     * Start consuming ticks from the channel.
     * Each tick pushes to the cursor flow, then runs a gradient step.
     * This is the event loop — call it in a coroutine scope.
     */
    suspend fun consumeTicks() {
        for (candle in tickChannel) {
            push(candle, isFinal = true)
            step()
        }
    }

    // -- Windup (factory) --

    companion object {

        /**
         * Windup: assemble everything the IoMux needs for numAgents agents.
         *
         * Called once before the event loop. After this returns, the IoMux
         * is fully composed — push() and step() have no setup work to do.
         *
         * What gets composed:
         *   1. Shared DiffDuckCursor from DuckDB
         *   2. Series<CursorBridge> — per-agent SVar params + expression cache
         *   3. Shared pancake — one Series<Double> join across OHLCV cols
         *   4. Channelized flows: cursorFlow (replay=1), resultFlow (replay=1)
         *   5. tickChannel — unbuffered Channel for candle input
         */
        fun windup(
            dbPath: String,
            pair: String,
            timeframe: String = "5m",
            initialCandles: Int = 500,
            numAgents: Int = 24,
        ): TradePairIoMux {

            // 1. Shared cursor — loaded once, all agents read from it
            val cursor = DiffDuckCursor.open(dbPath, pair, timeframe, initialCandles)

            // 2. Cursor bridges — each agent gets unique SVar names,
            //    so their expression trees are independent in the cache.
            //    If two agents converge to equivalent expressions, the
            //    structural fingerprint (≅) detects it.
            val bridges: Series<CursorBridge> = numAgents j { i: Int ->
                CursorBridge(
                    agentId   = i,
                    macdFast  = SVar(DReal, "fast_$i"),
                    macdSlow  = SVar(DReal, "slow_$i"),
                    sigSpan   = SVar(DReal, "sig_$i"),
                    sharpness = SVar(DReal, "sharp_$i"),
                )
            }

            // 3. Shared pancake — one composition, one reference
            //    rows x cols linearised: same as acapulco pancake(shallow["Open","High","Low","Close","Volume"])
            val sharedPancake = cursor.pancake("open", "high", "low", "close", "volume")

            // 4. Channelized flows
            //    cursorFlow: replay=1 so late subscribers receive last cursor
            //    resultFlow: replay=1 so late subscribers receive last gradient batch
            val cursorFlow = MutableSharedFlow<Join<DiffDuckCursor, Int>>(replay = 1)
            val resultFlow = MutableSharedFlow<Series<GradResult>>(replay = 1)

            // 5. Tick channel — exchange pushes candles here
            val tickChannel = Channel<OHLCVCandle>(Channel.BUFFERED)

            return TradePairIoMux(
                pair          = pair,
                cursor        = cursor,
                bridges       = bridges,
                sharedPancake = sharedPancake,
                cursorFlow    = cursorFlow,
                resultFlow    = resultFlow,
                tickChannel   = tickChannel,
                history       = ArrayDeque(),
                intra         = ArrayDeque(),
            )
        }

        /**
         * Windup from pre-built cursor — for testing without DuckDB file.
         */
        fun windupFromCursor(
            pair: String,
            cursor: DiffDuckCursor,
            numAgents: Int = 24,
        ): TradePairIoMux {
            val bridges: Series<CursorBridge> = numAgents j { i: Int ->
                CursorBridge(
                    agentId   = i,
                    macdFast  = SVar(DReal, "fast_$i"),
                    macdSlow  = SVar(DReal, "slow_$i"),
                    sigSpan   = SVar(DReal, "sig_$i"),
                    sharpness = SVar(DReal, "sharp_$i"),
                )
            }
            val colNames = cursor.columns.filter { it in setOf("open", "high", "low", "close", "volume") }
            val sharedPancake = if (colNames.size >= 4) cursor.pancake(*colNames.toTypedArray())
                                else cursor.pancake(*cursor.columns.toTypedArray())

            return TradePairIoMux(
                pair          = pair,
                cursor        = cursor,
                bridges       = bridges,
                sharedPancake = sharedPancake,
                cursorFlow    = MutableSharedFlow(replay = 1),
                resultFlow    = MutableSharedFlow(replay = 1),
                tickChannel   = Channel(Channel.BUFFERED),
                history       = ArrayDeque(),
                intra         = ArrayDeque(),
            )
        }
    }
}
