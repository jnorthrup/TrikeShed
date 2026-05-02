package borg.trikeshed.couch.handle

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.miniduck.*
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * In-memory collection handle for appending rows and producing immutable snapshots.
 * Thread-unsafe — use synchronization at the call site when needed.
 */
class CollectionHandle constructor() {
    var _state: HandleState = HandleState.OPEN
    val rows: MutableList<RowVec> = mutableListOf()

    val state: HandleState get() = _state
    val rowCount: Int get() = rows.size

    companion object {
        fun open(): CollectionHandle = CollectionHandle()
    }

    fun append(row: Any?) {
        check(_state == HandleState.OPEN) { "Cannot append unless OPEN" }
        val rv: RowVec = when (row) {
            is borg.trikeshed.lib.Join<*,*> -> row as RowVec
            is borg.trikeshed.miniduck.DocRowVec -> row.toRowVec()
            is borg.trikeshed.miniduck.ViewRowVec -> row.toRowVec()
            else -> error("Unsupported row type: ${row?.let { it::class }}")
        }
        rows.add(rv)
    }

    fun seal() {
        check(_state == HandleState.OPEN) { "Can only seal from OPEN state" }
        _state = HandleState.SEALED
    }

    fun close() {
        if (_state == HandleState.OPEN) _state = HandleState.SEALED
        _state = HandleState.CLOSED
    }

    fun snapshot(): Series<RowVec> {
        check(_state != HandleState.CLOSED) { "Cannot snapshot a CLOSED handle" }
        return rows.size j { rows[it] }
    }
}
