package borg.literbike.http_htx

/**
 * HTTP-HTX Reactor - event loop
 *
 * This module CANNOT see matcher, timer, handler.
 */

import kotlin.native.concurrent.AtomicBoolean
import kotlin.native.concurrent.AtomicLong

/**
 * ReactorKey - reactor factory
 */
object ReactorKey {
    const val DEFAULT_TIMEOUT_MS: ULong = 100uL
    val FACTORY: () -> ReactorElement = { ReactorElement() }
}

/**
 * ReactorElement - event loop state
 */
class ReactorElement {
    private val running = AtomicBoolean(false)
    private val selectCalls = AtomicLong(0)
    private val eventsDispatched = AtomicLong(0)
    var timeoutMs: ULong = ReactorKey.DEFAULT_TIMEOUT_MS

    fun start() { running.value = true }
    fun stop() { running.value = false }
    fun isRunning(): Boolean = running.value
    fun tick() { selectCalls.incrementAndGet() }
    fun dispatch() { eventsDispatched.incrementAndGet() }
    fun selectCalls(): ULong = selectCalls.get().toULong()
    fun eventsDispatched(): ULong = eventsDispatched.get().toULong()
}

/**
 * Ready event from reactor
 */
data class ReadyEvent(
    val fd: Int,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val error: Boolean = false
)

/**
 * Interest set for reactor registration
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
