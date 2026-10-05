package borg.trikeshed.narsese

import borg.trikeshed.lib.*

/**
 * Sense rows ([SenseRow]) as one atlas: each row is a chart on its K-class sphere, and the atlas lays every chart's
 * evidence out as flat Int columns so that a decision over all of them is one plain loop the JIT can vectorize.
 *
 * Charts are in CSR form: chart r owns cells [start] r until [start] r+1; per cell the class id ([cls]) and mass ([w],
 * milli-units); per chart T ([t]) and K ([k]). A chart whose menus differ ([censored]) has no integer counts (its masses
 * are read off the EM fixed point), so its cells' holds are decided by the row when the atlas is built and carried in [rhs].
 *
 * The decisions are the planes of [SenseRow], computed as the sign bit of an Int difference and summed in the same pass:
 * - eternal: 2(den − num)·T − num·K·UNIT ≥ 0 ([SenseRow.eternal]);
 * - holds, per cell: den·(2w + UNIT) − num·(2T + K·UNIT) ≥ 0, the second term carried per cell in [rhs] ([SenseRow.holds]).
 * Int columns hold while T < [T_LIMIT] milli-units (10⁸, 100,000 judgments in one chart); [of] refuses more.
 */
class SenseAtlas private constructor(
    val charts: Series<SenseRow>,
    val start: IntArray,
    val cls: IntArray,
    val w: IntArray,
    val rhs: IntArray,
    val t: IntArray,
    val k: IntArray,
    val censored: BooleanArray,
) {
    val size: Int get() = t.size
    val cells: Int get() = cls.size

    /** Per chart 1 where it is eternal; returns how many are. One fused pass over two Int columns. */
    fun eternal(mask: ByteArray, num: Int = SenseRow.ETERNAL_NUM.toInt(), den: Int = SenseRow.ETERNAL_DEN.toInt()): Int {
        val a = 2 * (den - num); val b = num * UNIT
        val t = t; val k = k
        var c = 0
        for (r in 0 until t.size) {
            val bit = ((a * t[r] - b * k[r]) ushr 31) xor 1
            mask[r] = bit.toByte(); c += bit
        }
        return c
    }

    /** Per cell 1 where its class holds at 7/10; returns how many hold. One fused pass over two Int columns. */
    fun holds(mask: ByteArray): Int {
        val den = SenseRow.HOLDS_DEN.toInt()
        val w = w; val rhs = rhs
        var c = 0
        for (x in 0 until w.size) {
            val bit = ((den * (2 * w[x] + UNIT) - rhs[x]) ushr 31) xor 1
            mask[x] = bit.toByte(); c += bit
        }
        return c
    }

    /**
     * Per chart the cell index its production types the word as ([SenseRow.production]): its leading class other than
     * none, when the chart is eternal and that cell holds; else -1. Returns how many charts produce.
     */
    fun productions(out: IntArray, eternal: ByteArray = ByteArray(size).also { eternal(it) }, holds: ByteArray = ByteArray(cells).also { holds(it) }): Int {
        var c = 0
        for (r in 0 until size) {
            out[r] = -1
            if (eternal[r].toInt() == 0) continue
            val top = if (censored[r]) charts[r].top.let { if (it < 0) -1 else start[r] + it } else leading(r)
            if (top >= 0 && holds[top].toInt() == 1) { out[r] = top; c++ }
        }
        return c
    }

    /** The first cell of chart [r] with the most mass, the none class excepted; -1 when it holds only none. */
    private fun leading(r: Int): Int {
        var best = -1
        for (x in start[r] until start[r + 1]) if (cls[x] != noneOf[r] && (best < 0 || w[x] > w[best])) best = x
        return best
    }

    private val noneOf = IntArray(size) { charts[it].noneClass }

    companion object {
        const val UNIT = Nal.UNIT.toInt()

        /** The largest chart mass the Int columns carry exactly, in milli-units. */
        const val T_LIMIT = 100_000_000L

        /** The atlas of [rows]. */
        fun of(rows: Series<SenseRow>): SenseAtlas {
            val n = rows.size
            val start = IntArray(n + 1)
            for (r in 0 until n) start[r + 1] = start[r] + rows[r].width
            val cls = IntArray(start[n]); val w = IntArray(start[n]); val rhs = IntArray(start[n])
            val t = IntArray(n); val k = IntArray(n); val censored = BooleanArray(n)
            val num = SenseRow.HOLDS_NUM.toInt()
            for (r in 0 until n) {
                val row = rows[r]
                require(row.mass < T_LIMIT) { "chart $r holds ${row.mass} milli-units, past the Int columns' $T_LIMIT" }
                t[r] = row.mass.toInt(); k[r] = row.width
                censored[r] = !row.exact
                val plane = num * (2 * t[r] + row.width * UNIT)
                for (i in 0 until row.width) {
                    val x = start[r] + i
                    cls[x] = row.classes[i]; w[x] = row.positive[i].toInt()
                    // A censored chart's cell is decided once, by the row's fixed point: rhs 0 holds, Int.MAX_VALUE does not
                    // (the left side stays under 2.0000·10⁹ while T < T_LIMIT).
                    rhs[x] = if (!censored[r]) plane else if (row.holds(i)) 0 else Int.MAX_VALUE
                }
            }
            return SenseAtlas(rows, start, cls, w, rhs, t, k, censored)
        }

        fun of(rows: List<SenseRow>): SenseAtlas = of(rows.size j { i: Int -> rows[i] })
    }
}
