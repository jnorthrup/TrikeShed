package borg.trikeshed.couch.control

/** JS actual: simple single-threaded admission control (sufficient for JS) */
actual class AdmissionControl actual constructor(private val capacity: Int) {

    private var permits: Int = capacity

    private enum class State { OPEN, SEALED, CLOSED }
    private var _state: State = State.OPEN

    actual fun tryAcquire(): Boolean {
        if (_state != State.OPEN) return false
        if (capacity <= 0) return false
        if (permits <= 0) return false
        permits -= 1
        return true
    }

    actual fun release() {
        val next = permits + 1
        permits = if (capacity >= 0) minOf(next, capacity) else next
    }

    actual fun seal() {
        _state = State.SEALED
    }

    actual fun close() {
        _state = State.CLOSED
    }
}
