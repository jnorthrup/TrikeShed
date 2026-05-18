package borg.trikeshed.miniduck.exec

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.getValue
import borg.trikeshed.cursor.keys
import borg.trikeshed.cursor.values
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.size

/** Positioned cursor API used by MiniDuck integration surfaces. */
interface Cursor {
    fun next(): Boolean
    val row: RowAccessor
    fun close()
}

class SeriesCursor(private val rows: Series<RowVec>) : Cursor {
    private var index = -1

    override fun next(): Boolean {
        val nextIndex = index + 1
        return if (nextIndex < rows.size) {
            index = nextIndex
            true
        } else {
            false
        }
    }

    override val row: RowAccessor
        get() {
            require(index in 0 until rows.size) { "cursor is not positioned on a row" }
            return RowVecRowAccessor(rows[index])
        }

    override fun close() {
        // no-op
    }
}

fun emptyCursor(): Cursor = SeriesCursor(emptySeries<RowVec>())

private class RowVecRowAccessor(
    private val row: RowVec,
) : RowAccessor {
    override fun get(index: Int): Any? = row.values[index]
    override fun get(name: String): Any? = row.getValue(name)
    override val size: Int get() = row.size
    override fun columnName(index: Int): String? = if (index in 0 until row.keys.size) row.keys[index] else null
}
