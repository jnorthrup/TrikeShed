@file:Suppress("NonAsciiCharacters", "FunctionName")

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

// -- CursorBridge --
//
// Each agent gets one CursorBridge assembled in windup.
// Holds pre-composed SVar parameters and handles the gradient step.

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
     */
    fun step(cursor: DiffDuckCursor): GradResult {
        // 1. Fold: Series<Double> x SVar -> Series<SFun<DReal>> -> SFun scalar
        val macd   = cursor.macdFold("close", macdFast, macdSlow)
        val signal = cursor.emaFold("close", sigSpan)
        val buy    = cursor.softCrossoverFold(macd, signal, sharpness)
        val sell   = cursor.softCrossoverFold(signal, macd, sharpness)
        val pnl    = cursor.softPnlFold("close", buy, sell)

        // 2. Evaluate at current param values
        val b = bindings
        val pnlVal = pnl.evalDouble(b)

        // 3. Gradients — d(pnl)/d(each param)
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

// -- ITradePairEventMuxer --

interface ITradePairEventMuxer {
    val pair: String
    val cursorFlow: SharedFlow<Join<DiffDuckCursor, Int>>
    suspend fun push(candle: OHLCVCandle, isFinal: Boolean)
}

// -- TradePairIoMux --

class TradePairIoMux private constructor(
    override val pair: String,
    val cursor: DiffDuckCursor,
    val bridges: Series<CursorBridge>,
    val sharedPancake: Series<Double>,
    override val cursorFlow: MutableSharedFlow<Join<DiffDuckCursor, Int>>,
    val resultFlow: MutableSharedFlow<Series<GradResult>>,
    val tickChannel: Channel<OHLCVCandle>,
    private val history: ArrayDeque<OHLCVCandle>,
    private val intra: ArrayDeque<OHLCVCandle>,
) : ITradePairEventMuxer {

    val numAgents: Int get() = bridges.a

    override suspend fun push(candle: OHLCVCandle, isFinal: Boolean) {
        if (isFinal) {
            history.addLast(candle)
            intra.clear()
        } else {
            intra.addLast(candle)
        }
        cursorFlow.emit(cursor j intra.size)
    }

    /**
     * Fan out to all agents concurrently.
     * Each agent uses its pre-composed CursorBridge.
     */
    suspend fun step(): Series<GradResult> = coroutineScope {
        val n = bridges.a
        val results = (0 until n).map { i ->
            async(Dispatchers.Default) {
                bridges[i].step(cursor)
            }
        }.awaitAll()
        val series: Series<GradResult> = n j { results[it] }
        resultFlow.emit(series)
        series
    }

    suspend fun consumeTicks() {
        for (candle in tickChannel) {
            push(candle, isFinal = true)
            step()
        }
    }

    companion object {
        /**
         * Windup: assemble everything the IoMux needs for numAgents agents.
         */
        fun windup(
            dbPath: String,
            pair: String,
            timeframe: String = "5m",
            initialCandles: Int = 500,
            numAgents: Int = 24,
        ): TradePairIoMux {
            val cursor = DiffDuckCursor.open(dbPath, pair, timeframe, initialCandles)
            val bridges: Series<CursorBridge> = numAgents j { i: Int ->
                CursorBridge(
                    agentId   = i,
                    macdFast  = SVar(DReal, "fast_$i"),
                    macdSlow  = SVar(DReal, "slow_$i"),
                    sigSpan   = SVar(DReal, "sig_$i"),
                    sharpness = SVar(DReal, "sharp_$i"),
                )
            }
            val sharedPancake = cursor.pancake("open", "high", "low", "close", "volume")
            val cursorFlow = MutableSharedFlow<Join<DiffDuckCursor, Int>>(replay = 1)
            val resultFlow = MutableSharedFlow<Series<GradResult>>(replay = 1)
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
            val sharedPancake = cursor.pancake("open", "high", "low", "close", "volume")

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
