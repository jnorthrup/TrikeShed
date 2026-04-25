package borg.trikeshed.couch.control

import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM-specific AdmissionControl implementation using AtomicInteger and @Volatile for correctness
 */
class AdmissionControl(private val capacity: Int) {

    private val permits = AtomicInteger(capacity)

    private enum class State { OPEN, SEALED, CLOSED }
    @Volatile
    private var _state: State = State.OPEN

    fun tryAcquire(): Boolean {
        if (_state != State.OPEN) return false
        if (capacity <= 0) return false
        while (true) {
            val cur = permits.get()
            if (cur <= 0) return false
            if (permits.compareAndSet(cur, cur - 1)) return true
        }
    }

    fun release() {
        permits.updateAndGet { cur ->
            val next = cur + 1
            if (capacity >= 0) minOf(next, capacity) else next
        }
    }

    fun seal() {
        _state = State.SEALED
    }

    fun close() {
        _state = State.CLOSED
    }
}
