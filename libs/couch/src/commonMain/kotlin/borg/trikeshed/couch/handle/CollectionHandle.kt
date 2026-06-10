package borg.trikeshed.couch.handle

import borg.trikeshed.miniduck.MiniRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * In-memory collection handle for appending rows and producing immutable snapshots.
 * Thread-unsafe — use synchronization at the call site when needed.
 */
class CollectionHandle constructor() {
    var _state: HandleState = HandleState.OPEN
    val rows: MutableList<MiniRowVec> = mutableListOf()

    val state: HandleState get() = _state
    val rowCount: Int get() = rows.size

    companion object {
        fun open(): CollectionHandle = CollectionHandle()
    }

    fun append(row: MiniRowVec) {
        check(_state == HandleState.OPEN) { "Cannot append unless OPEN" }
        rows.add(row)
    }

    fun seal() {
        check(_state == HandleState.OPEN) { "Can only seal from OPEN state" }
        _state = HandleState.SEALED
    }

    fun close() {
        if (_state == HandleState.OPEN) _state = HandleState.SEALED
        _state = HandleState.CLOSED
    }

    fun snapshot(): Series<MiniRowVec> {
        check(_state != HandleState.CLOSED) { "Cannot snapshot a CLOSED handle" }
        return rows.size j { rows[it] }
    }
}
