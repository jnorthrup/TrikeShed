package borg.trikeshed.couch.kline

    import borg.trikeshed.cursor.Cursor
    import borg.trikeshed.miniduck.toRowVec
    import borg.trikeshed.lib.j
    import borg.trikeshed.lib.toSeries
    import borg.trikeshed.isam.meta.IOMemento

/**
 * KlineBlock: DuckDB-style mutable→sealed chunk of klines.
 *
 * One writer appends klines during MUTABLE phase.
 * Seal() is the irreversible sync boundary — readers only see sealed blocks.
 *
 * Donor patterns:
 *   - TradePairEventMuxer: accumulates CandlestickEvents in ArrayList,
 *     then flushes to ISAM on episode cutoff
 *   - BlockRowVec: the MUTABLE→SEALED state machine already established
 *     in TrikeShed miniduck
 *   - DataBinanceVision.klines: the schema (Open_time, Open, High, Low, Close, Volume)
 *
 * Single-timespan invariant: all klines in a block must share the same TimeSpan.
 * The block may also enforce a single symbol (currently not enforced; symbol is
 * carried per-row as in the donor).
 */
class KlineBlock constructor(
    val rows: MutableList<Kline>,
    var _state: State,
    val timespan: TimeSpan?,
) {

    enum class State { MUTABLE, SEALED }

    val state: State get() = _state
    val rowCount: Int get() = rows.size

    companion object {
        /** Create a new mutable block. Optionally enforce a single timespan. */
        fun mutable(timespan: TimeSpan? = null): KlineBlock =
            KlineBlock(mutableListOf(), State.MUTABLE, timespan)
    }

    /**
     * Append a kline to this mutable block.
     *
     * @throws IllegalStateException if the block is sealed.
     * @throws IllegalArgumentException if the kline's timespan doesn't match the block's.
     */
    fun append(kline: Kline) {
        check(_state == State.MUTABLE) { "Cannot append to sealed block" }
        if (timespan != null && kline.timespan != timespan) {
            throw IllegalArgumentException(
                "Mixed timespan: block expects $timespan but got ${kline.timespan}"
            )
        }
        rows.add(kline)
    }

    /**
     * Seal the block — irreversible.
     * Returns this same block for fluent usage.
     *
     * @throws IllegalStateException if already sealed.
     */
    fun seal(): KlineBlock {
        check(_state == State.MUTABLE) { "Already sealed" }
        _state = State.SEALED
        return this
    }

    /**
     * Present the sealed block as a MiniCursor of DocRowVec.
     *
     * Each row is a Kline.toDocRowVec() — the standard OHLCV keys.
     * The cursor is a lazy Series: rows are projected on access.
     *
     * @throws IllegalStateException if the block is not sealed.
     */
    fun asCursor(): Cursor {
        check(_state == State.SEALED) { "Block must be sealed before presenting as cursor" }
        // Snapshot the rows list to avoid concurrent modification
        val snapshot = rows.toList()
        return snapshot.size j { i: Int ->
            snapshot[i].toDocRowVec().toRowVec()
        }
    }

    /**
     * Present the sealed block as a columnar Cursor (cursor.RowVec rows).
     *
     * Each row is projected as a RowVec of values joined with ColumnMeta
     * inferred from the cell values. This allows feeding blocks into
     * Columnar/ISAM paths without going through MiniCursor.
     */
    fun asColumnarCursor(): borg.trikeshed.cursor.Cursor {
        check(_state == State.SEALED) { "Block must be sealed before presenting as cursor" }
        val snapshot = rows.toList()
        return snapshot.size j { i: Int ->
            val k = snapshot[i]
            val cells: List<Any?> = listOf(k.symbol, k.timespan.toString(), k.openTime, k.open, k.high, k.low, k.close, k.volume)
            val values = cells.toSeries()
            val meta = cells.size j { idx: Int ->
                val v = cells[idx]
                val t = when (v) {
                    is Double -> IOMemento.IoDouble
                    is Float -> IOMemento.IoFloat
                    is Long -> IOMemento.IoLong
                    is Int -> IOMemento.IoInt
                    is Boolean -> IOMemento.IoBoolean
                    else -> IOMemento.IoString
                }
                { Kline.schemaKeys[idx] j t }
            }
            (values.j(meta) as borg.trikeshed.cursor.RowVec)
        }
    }
}
