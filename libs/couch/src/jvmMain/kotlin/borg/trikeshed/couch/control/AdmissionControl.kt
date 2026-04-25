package borg.trikeshed.couch.control

import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM-specific AdmissionControl implementation using AtomicInteger and @Volatile for correctness
 */
actual class AdmissionControl actual constructor(private val capacity: Int) {

    private val permits = AtomicInteger(capacity)

    private enum class State { OPEN, SEALED, CLOSED }
    @Volatile
    private var _state: State = State.OPEN

    actual fun tryAcquire(): Boolean {
        if (_state != State.OPEN) return false
        if (capacity <= 0) return false
        while (true) {
            val cur = permits.get()
            if (cur <= 0) return false
            if (permits.compareAndSet(cur, cur - 1)) return true
        }
    }

    actual fun release() {
        permits.updateAndGet { cur ->
            val next = cur + 1
            if (capacity >= 0) minOf(next, capacity) else next
        }
    }

    actual fun seal() {
        _state = State.SEALED
    }

    actual fun close() {
        _state = State.CLOSED
    }
}
