package borg.trikeshed.couch.handle

import borg.trikeshed.couch.miniduck.MiniRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/** wasm-js actual CollectionHandle — single-threaded semantics */
actual class CollectionHandle {

    private var _state: HandleState = HandleState.OPEN
    private val rows: MutableList<MiniRowVec> = mutableListOf()

    actual val state: HandleState get() = _state
    actual val rowCount: Int get() = rows.size

    actual companion object {
        actual fun open(): CollectionHandle = CollectionHandle()
    }

    actual fun append(row: MiniRowVec) {
        check(_state == HandleState.OPEN) { "Cannot append unless OPEN" }
        rows.add(row)
    }

    actual fun seal() {
        check(_state == HandleState.OPEN) { "Can only seal from OPEN state" }
        _state = HandleState.SEALED
    }

    actual fun close() {
        if (_state == HandleState.OPEN) _state = HandleState.SEALED
        _state = HandleState.CLOSED
    }

    actual fun snapshot(): Series<MiniRowVec> {
        check(_state != HandleState.CLOSED) { "Cannot snapshot a CLOSED handle" }
        val snap = rows.toList()
        return snap.size j { i: Int -> snap[i] }
    }
}
