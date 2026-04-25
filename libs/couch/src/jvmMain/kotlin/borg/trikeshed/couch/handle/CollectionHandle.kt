package borg.trikeshed.couch.handle

import borg.trikeshed.couch.miniduck.MiniRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * JVM-specific CollectionHandle with @Volatile for state
 */
actual class CollectionHandle private constructor() {

    @Volatile
    private var _state: HandleState = HandleState.OPEN

    private val rows: MutableList<MiniRowVec> = mutableListOf()

    private val lock = Any()

    actual val state: HandleState get() = _state

    actual val rowCount: Int get() = rows.size

    actual companion object {
        actual fun open(): CollectionHandle = CollectionHandle()
    }

    /** Append a row to the open handle. Throws if not OPEN. */
    actual fun append(row: MiniRowVec) {
        synchronized(lock) {
            check(_state == HandleState.OPEN) { "Cannot append unless OPEN" }
            rows.add(row)
        }
    }

    /** Seal the handle so no further appends are allowed; snapshots remain available. */
    actual fun seal() {
        synchronized(lock) {
            check(_state == HandleState.OPEN) { "Can only seal from OPEN state" }
            _state = HandleState.SEALED
        }
    }

    /** Close the handle. After close, snapshot and append are illegal. */
    actual fun close() {
        synchronized(lock) {
            // allow sealing as part of close transition
            if (_state == HandleState.OPEN) _state = HandleState.SEALED
            _state = HandleState.CLOSED
        }
    }

    /** Present an immutable snapshot Series of the current rows.
     * If handle is CLOSED, snapshot is not allowed.
     */
    actual fun snapshot(): Series<MiniRowVec> {
        synchronized(lock) {
            check(_state != HandleState.CLOSED) { "Cannot snapshot a CLOSED handle" }
            val snap = rows.toList()
            return snap.size j { i: Int -> snap[i] }
        }
    }
}
