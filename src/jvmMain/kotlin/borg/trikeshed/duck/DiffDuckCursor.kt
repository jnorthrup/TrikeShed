@file:Suppress("NonAsciiCharacters", "FunctionName")

package borg.trikeshed.duck

import ai.hypergraph.kotlingrad.api.*
import borg.trikeshed.lib.*

/**
 * DiffDuckCursor — DuckDB cursor whose columns are Series<Double> (TrikeShed native).
 *
 * Parameters (SVar) join at computation time via fold methods, producing
 * SFun<DReal> scalars. The Series infrastructure carries data; kotlingrad
 * carries the symbolic math over parameters.
 *
 * Read-only, shareable across all 24 codec swimlanes.
 * Each codec brings its own SVar params and folds against the same columns.
 *
 * Acapulco lineage:
 *   ITradePairEventMuxer  ->  Flow<Join<Cursor, Int>>
 *   decorateView          ->  join(wallet, contextInfo, bookCursor, candles)
 *   pancake               ->  flatten rows x cols into 1-D feature vector
 *
 * Here:
 *   DiffDuckCursor        ->  Series<Double> columns joined with SVar params
 *   diffFold              ->  Series<Double> x params -> SFun<DReal> (scalar)
 *   CursorBridge          ->  adapter: Series<Double> <-> Series<SFun<DReal>>
 */
class DiffDuckCursor private constructor(
    private val cols: Map<String, Series<Double>>
) {
    val size: Int = cols.values.first().a

    /** Raw data column — Series<Double>, TrikeShed native. */
    operator fun get(col: String): Series<Double> =
        cols[col] ?: error("Column not found: $col. Available: ${cols.keys}")

    val columns: Set<String> get() = cols.keys

    /**
     * Expression cache — idempotent by structural key.
     * Two different seats calling emaFold("close", spanVar) where spanVar
     * has the same name land on the same Series<SFun<DReal>> reference.
     */
    private val exprCache = mutableMapOf<String, Series<SFun<DReal>>>()

    // -- Series join helpers --

    /** Join two columns element-wise via TrikeShed j. */
    fun zip(a: String, b: String): Series<Join<Double, Double>> {
        val sa = this[a]; val sb = this[b]
        return size j { i: Int -> sa[i] j sb[i] }
    }

    /**
     * Join a data column with a parameter broadcast across all rows.
     * This is the join that makes computation differentiable:
     * data row + param in the same Series slot, ready for folding.
     */
    fun joinParam(col: String, param: SFun<DReal>): Series<Join<Double, SFun<DReal>>> =
        size j { i: Int -> this[col][i] j param }

    /**
     * Pancake: flatten columns over the window into a 1-D Series.
     * Same as acapulco's pancake() — rows x cols linearised by index.
     */
    fun pancake(vararg colNames: String): Series<Double> {
        val n = colNames.size
        return (size * n) j { idx: Int ->
            val row = idx / n
            val col = idx % n
            this[colNames[col]][row]
        }
    }

    // -- Differentiable folds (Series<Double> x SVar -> SFun<DReal>) --

    /**
     * EMA fold — cached by (col, param key).
     *
     *   ema[0] = wrap(close[0])
     *   ema[i] = alpha * close[i] + (1-alpha) * ema[i-1]
     *
     * The entire recursion stays in the SFun expression graph so that
     * d(ema[N])/d(span) works without manual chain-rule bookkeeping.
     */
    fun emaFold(col: String, span: SFun<DReal>): Series<SFun<DReal>> {
        val key = "ema:$col:${span.exprKey}"
        return exprCache.getOrPut(key) {
            val data = this[col]
            val alpha: SFun<DReal> = DReal.wrap(2.0) / (span + DReal.wrap(1.0))
            val one: SFun<DReal> = DReal.wrap(1.0)
            val buf = arrayOfNulls<SFun<DReal>>(size)
            buf[0] = DReal.wrap(data[0])
            for (i in 1 until size) {
                buf[i] = alpha * DReal.wrap(data[i]) + (one - alpha) * buf[i - 1]!!
            }
            @Suppress("UNCHECKED_CAST")
            val arr = buf as Array<SFun<DReal>>
            size j { arr[it] }
        }
    }

    /**
     * MACD fold — cached by (col, fast key, slow key).
     * Reuses two emaFold entries if already cached.
     */
    fun macdFold(
        col: String,
        fast: SFun<DReal>,
        slow: SFun<DReal>
    ): Series<SFun<DReal>> {
        val key = "macd:$col:${fast.exprKey}:${slow.exprKey}"
        return exprCache.getOrPut(key) {
            val f = emaFold(col, fast)
            val s = emaFold(col, slow)
            size j { f[it] - s[it] }
        }
    }

    /**
     * Soft crossover: sigmoid(k * (macd - signal)).
     * Replaces hard step with smooth gate in (0,1).
     * k (sharpness) is SFun — gradient flows through k too.
     */
    fun softCrossoverFold(
        macdSeries: Series<SFun<DReal>>,
        signalSeries: Series<SFun<DReal>>,
        sharpness: SFun<DReal>
    ): Series<SFun<DReal>> =
        size j { i: Int ->
            sigmoid(sharpness * (macdSeries[i] - signalSeries[i]))
        }

    /**
     * Soft P&L scalar: sum of position[i] * return[i].
     *
     * position[i] = buySig[i] - sellSig[i]  in (-1, 1)
     * return[i]   = (close[i+1] - close[i]) / close[i]
     *
     * Produces one SFun<DReal> scalar over the entire window.
     * d(pnl)/d(any SVar) is a single .d(param) call.
     */
    fun softPnlFold(
        closeCol: String,
        buySig: Series<SFun<DReal>>,
        sellSig: Series<SFun<DReal>>
    ): SFun<DReal> {
        val close = this[closeCol]
        var pnl: SFun<DReal> = DReal.wrap(0.0)
        for (i in 0 until size - 1) {
            val position = buySig[i] - sellSig[i]
            val ret = DReal.wrap((close[i + 1] - close[i]) / close[i])
            pnl = pnl + position * ret
        }
        return pnl
    }

    // -- Factory --

    companion object {
        /**
         * Open from DuckDB via TrikeShed's DuckSeries.
         * Uses the existing JDBC infrastructure — no raw DriverManager.
         */
        fun open(
            dbPath: String,
            pair: String,
            timeframe: String = "5m",
            limit: Int = 500
        ): DiffDuckCursor {
            val db = DuckSeries(dbPath)
            val colMap = db.columns(
                """SELECT date, open, high, low, close, volume
                   FROM ohlcv
                   WHERE pair = ? AND timeframe = ?
                   ORDER BY date
                   LIMIT ?""",
                pair, timeframe, limit
            )
            db.close()
            return DiffDuckCursor(colMap.mapValues { (_, s) ->
                val n = s.a
                n j { i: Int -> (s[i] as Number).toDouble() }
            })
        }

        /** Bridge from any Map<String, Series<Double>> — same contract. */
        fun fromColumns(map: Map<String, Series<Double>>): DiffDuckCursor =
            DiffDuckCursor(map)

        /** In-memory from raw DoubleArrays — useful for tests. */
        fun ofArrays(vararg named: Join<String, DoubleArray>): DiffDuckCursor =
            DiffDuckCursor(named.associate { (name, arr) ->
                name to (arr.size j arr::get)
            })
    }
}

// -- Sigmoid helper --

private fun sigmoid(x: SFun<DReal>): SFun<DReal> {
    val one: SFun<DReal> = DReal.wrap(1.0)
    return one / (one + E<DReal>().pow(-x))
}

// -- SFun expression key (SVar has name, other nodes use toString hash) --

val SFun<DReal>.exprKey: String
    get() = when (this) {
        is SVar -> name
        else -> toString().hashCode().toString()
    }

// -- Evaluate SFun<DReal> with a Map of bindings, return Double --

fun SFun<DReal>.evalDouble(bindings: Map<SVar<DReal>, Number>): Double {
    val pairs = bindings.entries.map { (k, v) -> k to v as Any }.toTypedArray()
    return invoke(*pairs).toDouble()
}
