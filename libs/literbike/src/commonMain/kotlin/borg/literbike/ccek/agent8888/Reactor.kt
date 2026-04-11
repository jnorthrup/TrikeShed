package borg.literbike.ccek.agent8888

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Agent8888 Reactor - event loop
 *
 * This module manages the event loop state.
 * It only knows about itself and the core traits.
 */

/**
 * ReactorKey - manages event loop
 */
object ReactorKey : Key<ReactorElement> {
    const val DEFAULT_TIMEOUT_MS: Long = 100L

    override fun factory(): ReactorElement = ReactorElement()
}

/**
 * ReactorElement - event loop state
 */
class ReactorElement(
    val timeoutMs: Long = ReactorKey.DEFAULT_TIMEOUT_MS
) : Element {
    private val running = AtomicBoolean(false)
    private val selectCalls = AtomicLong(0)
    private val eventsDispatched = AtomicLong(0)

    fun start() {
        running.set(true)
    }

    fun stop() {
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    fun tick() {
        selectCalls.incrementAndFetch()
    }

    fun dispatch() {
        eventsDispatched.incrementAndFetch()
    }

    fun selectCalls(): Long = selectCalls.get()

    override fun keyType(): Any = ReactorKey
    override fun asAny(): Any = this
}

/**
 * Ready event from selector
 */
data class ReadyEvent(
    val fd: Int,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val error: Boolean = false
)

/**
 * Interest set for registration
 */
data class InterestSet(
    val read: Boolean = false,
    val write: Boolean = false,
    val error: Boolean = false
) {
    companion object {
        fun read() = InterestSet(read = true)
        fun write() = InterestSet(write = true)
        fun readWrite() = InterestSet(read = true, write = true)
    }
}
