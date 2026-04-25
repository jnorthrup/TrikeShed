package borg.trikeshed.couch.handle

import borg.trikeshed.couch.miniduck.MiniRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Platform-neutral CollectionHandle fallback for non-JVM targets. Keeps API
 * identical but uses simple Kotlin primitives to remain multi-platform.
 */
/** Global Handle namespace used by tests for state enum. */
object Handle {
    enum class State { OPEN, SEALED, CLOSED }
}

class CollectionHandle private constructor() {

    private var _state: Handle.State = Handle.State.OPEN

    private val rows: MutableList<MiniRowVec> = mutableListOf()

    val state: Handle.State get() = _state

    val rowCount: Int get() = rows.size

    companion object {
        fun open(): CollectionHandle = CollectionHandle()
    }

    /** Append a row to the open handle. Throws if not OPEN. */
    fun append(row: MiniRowVec) {
        check(_state == Handle.State.OPEN) { "Cannot append unless OPEN" }
        rows.add(row)
    }

    /** Seal the handle so no further appends are allowed; snapshots remain available. */
    fun seal() {
        check(_state == Handle.State.OPEN) { "Can only seal from OPEN state" }
        _state = Handle.State.SEALED
    }

    /** Close the handle. After close, snapshot and append are illegal. */
    fun close() {
        // allow sealing as part of close transition
        if (_state == Handle.State.OPEN) _state = Handle.State.SEALED
        _state = Handle.State.CLOSED
    }

    /** Present an immutable snapshot Series of the current rows.
     * If handle is CLOSED, snapshot is not allowed.
     */
    fun snapshot(): Series<MiniRowVec> {
        check(_state != Handle.State.CLOSED) { "Cannot snapshot a CLOSED handle" }
        val snap = rows.toList()
        return snap.size j { i: Int -> snap[i] }
    }
}
