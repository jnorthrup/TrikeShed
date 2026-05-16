package borg.trikeshed.couch.handle

import borg.trikeshed.lib.MetaSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.miniduck.DocRowVec

/**
 * In-memory collection handle for appending rows and producing immutable snapshots.
 * Thread-unsafe — use synchronization at the call site when needed.
 */
typealias DocMetaSeries = MetaSeries<Comparable<*>, DocRowVec>

data class DocMetaCount(val count: Int) : Comparable<DocMetaCount> {
    override fun compareTo(other: DocMetaCount): Int = count.compareTo(other.count)
}

val DocMetaSeries.size: Int
    get() = (a as? DocMetaCount)?.count
        ?: error("DocMetaSeries metadata must be DocMetaCount")

operator fun DocMetaSeries.get(key: Comparable<*>): DocRowVec = b(key)

class CollectionHandle constructor() {
    var _state: HandleState = HandleState.OPEN
    val rows: List<DocRowVec> = emptyList()

    val state: HandleState get() = _state
    val rowCount: Int get() = rows.size

    companion object {
        fun open(): CollectionHandle = CollectionHandle()
    }

    fun append(row: DocRowVec) {
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

    fun snapshot(): Series<DocRowVec> {
        check(_state != HandleState.CLOSED) { "Cannot snapshot a CLOSED handle" }
        val snapshot = rows.toList()
        return snapshot.size j { snapshot[it] }
    }

    fun metaSnapshot(keyOf: (DocRowVec) -> Comparable<*>): DocMetaSeries {
        check(_state != HandleState.CLOSED) { "Cannot snapshot a CLOSED handle" }
        val snapshot = rows.toList()
        val byKey: Map<Comparable<*>, DocRowVec> = snapshot.associate { row ->
            val key = keyOf(row)
            check(snapshot.count { keyOf(it) == key } == 1) { "Duplicate doc key in snapshot: $key" }
            key to row
        }
        return DocMetaCount(snapshot.size) j { key: Comparable<*> ->
            byKey[key] ?: error("Missing doc for key $key")
        }
    }

    fun snapshot(): Series<DocRowVec> {
        check(_state != HandleState.CLOSED) { "Cannot snapshot a CLOSED handle" }
        val snapshot = rows.toList()
        return snapshot.size j { snapshot[it] }
    }
}
