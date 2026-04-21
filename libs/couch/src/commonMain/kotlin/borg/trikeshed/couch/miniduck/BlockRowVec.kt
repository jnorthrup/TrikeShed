package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*

/**
 * BlockRowVec: DuckDB-style mutable -> sealed chunky block.
 *
 * State machine:
 *   MUTABLE: one writer, append allowed
 *   SEALED:  read-many, no further appends; sealing is the sync boundary
 *
 * Children expose the row families stored inside this block.
 */
class BlockRowVec private constructor(
    private val rows: MutableList<RowVec>,
    private var _sealed: Boolean,
) : RowVec() {

    enum class State { MUTABLE, SEALED }

    val state: State get() = if (_sealed) State.SEALED else State.MUTABLE

    /** BlockRowVec is itself a zero-length shell; its content is in children. */
    override val size: Int get() = 0
    override fun get(index: Int): Any? = throw IndexOutOfBoundsException("BlockRowVec is a shell; no scalar cells")

    /** Children are the rows stored in this block. */
    override val child: Series<RowVec>
        get() = rows.size j { rows[it] }

    /** Number of rows in the block. */
    val rowCount: Int get() = rows.size

    /** Append a row. Throws if sealed. */
    fun append(row: RowVec) {
        check(!_sealed) { "Cannot append to a sealed block" }
        rows.add(row)
    }

    /** Seal the block. After this call no further appends are allowed. Returns this for chaining. */
    fun seal(): BlockRowVec {
        _sealed = true
        return this
    }

    companion object {
        /** Create a new mutable block. */
        fun mutable(): BlockRowVec = BlockRowVec(mutableListOf(), false)
    }
}
