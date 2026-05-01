package borg.trikeshed.integration

import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.miniduck.getValue
import borg.trikeshed.cursor.at
import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.RowAccessor

/**
 * BinanceCursor: wraps a list of sealed KlineBlocks and presents them
 * as a flat [Cursor] of row-value rows for SQL/plan execution.
 *
 * Architecture:
 *   Binance CSV ZIP
 *     → BinanceCsvParser.parseCsv() → List<Kline>
 *     → KlineBlock.mutable() / append() / seal() → KlineBlock
 *     → BinanceCursor(KlineBlocks) → Cursor (row = cursor.RowVec)
 *
 * @param blocks sealed KlineBlocks; each block's asCursor() is a MiniCursor of cursor.RowVec.
 */
class BinanceCursor(
   val blocks: List<KlineBlock>,
) : Cursor {

    // Cursor position: block index, row index within block
   var blockIdx = 0
   var rowIdx = -1

    // Lazily built flat cursors
   val cursors: List<borg.trikeshed.cursor.Cursor> by lazy {
        blocks.map { it.asCursor() }
    }

   var totalRows: Int = -1

    init {
        require(blocks.all { it.state == KlineBlock.State.SEALED }) {
            "All blocks must be SEALED before constructing BinanceCursor"
        }
    }

    /** Total row count across all blocks. */
    fun rowCount(): Int {
        if (totalRows < 0) {
            totalRows = blocks.sumOf { it.rowCount }
        }
        return totalRows
    }

    override fun next(): Boolean {
        // First call: position at 0
        if (rowIdx < 0) {
            rowIdx = 0
            blockIdx = 0
            return rowCount() > 0
        }

        // Advance within current block
        val cur = cursors.getOrNull(blockIdx) ?: return false
        if (rowIdx + 1 < cur.size) {
            rowIdx++
            return true
        }

        // Advance to next block
        blockIdx++
        rowIdx = 0
        val nextCur = cursors.getOrNull(blockIdx)
        return nextCur != null && nextCur.size > 0
    }

    override val row: RowAccessor
        get() {
            val cur = cursors.getOrNull(blockIdx) ?: throw IllegalStateException("No current block")
            val docRow: RowVec = cur at  rowIdx
            return MiniRowVecRowAccessor(docRow)
        }

    override fun close() {
        // Stateless, nothing to close
    }
}

/**
 * Adapts a cursor row to the [RowAccessor] interface used by plan nodes.
 */
class MiniRowVecRowAccessor(
    val row: RowVec,
) : RowAccessor {
    override fun get(index: Int): Any? = row[index]
    override fun get(name: String): Any? {
        return row.getValue(name)
    }
}
