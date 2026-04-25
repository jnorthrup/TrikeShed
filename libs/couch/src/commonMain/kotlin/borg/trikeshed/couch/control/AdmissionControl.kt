package borg.trikeshed.couch.control

/**
 * Platform-neutral AdmissionControl fallback for non-JVM platforms.
 * This simplified implementation is single-threaded and intended to let
 * non-JVM targets compile; JVM tests use a JVM-specific implementation.
 */
class AdmissionControl(private val capacity: Int) {

    private var permits: Int = capacity

    private enum class State { OPEN, SEALED, CLOSED }
    private var _state: State = State.OPEN

    fun tryAcquire(): Boolean {
        if (_state != State.OPEN) return false
        if (capacity <= 0) return false
        if (permits <= 0) return false
        permits -= 1
        return true
    }

    fun release() {
        val next = permits + 1
        permits = if (capacity >= 0) minOf(next, capacity) else next
    }

    fun seal() {
        _state = State.SEALED
    }

    fun close() {
        _state = State.CLOSED
    }
}
