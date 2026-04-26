package borg.trikeshed.integration

import borg.trikeshed.couch.kline.KlineBlock
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import borg.trikeshed.miniduck.MiniRowVec
import borg.trikeshed.miniduck.at
import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.RowAccessor

/**
 * BinanceCursor: wraps a list of sealed KlineBlocks and presents them
 * as a flat [Cursor] of [MiniRowVec] (DocRowVec) rows for SQL/plan execution.
 *
 * Architecture:
 *   Binance CSV ZIP
 *     → BinanceCsvParser.parseCsv() → List<Kline>
 *     → KlineBlock.mutable() / append() / seal() → KlineBlock
 *     → BinanceCursor(KlineBlocks) → Cursor (row = MiniRowVec / DocRowVec)
 *
 * @param blocks sealed KlineBlocks; each block's asCursor() is a MiniCursor of DocRowVec.
 */
class BinanceCursor(
    private val blocks: List<KlineBlock>,
) : Cursor {

    // Cursor position: block index, row index within block
    private var blockIdx = 0
    private var rowIdx = -1

    // Lazily built flat cursors
    private val cursors: List<MiniCursor> by lazy {
        blocks.map { it.asCursor() }
    }

    private var totalRows: Int = -1

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
            // block.asCursor() returns MiniCursor whose rows are DocRowVec (MiniRowVec subtype)
            val docRow: MiniRowVec = cur at  rowIdx
            return MiniRowVecRowAccessor(docRow)
        }

    override fun close() {
        // Stateless, nothing to close
    }
}

/**
 * Adapts a [MiniRowVec] to the [RowAccessor] interface used by plan nodes.
 */
class MiniRowVecRowAccessor(
    private val row: MiniRowVec,
) : RowAccessor {
    override fun get(index: Int): Any? = row.get(index)
    override fun get(name: String): Any? {
        // DocRowVec supports name lookup; other MiniRowVec subtypes may not
        return (row as? DocRowVec)?.get(name)
    }
}
