package borg.literbike.http_htx

/**
 * HTTP-HTX Timer Wheel - Scheduled callbacks
 *
 * This module CANNOT see matcher, listener, handler.
 */

import kotlin.native.concurrent.AtomicLong

/**
 * Timer ID type
 */
typealias TimerId = ULong

/**
 * TimerKey - timer factory
 */
object TimerKey {
    val FACTORY: () -> TimerElement = { TimerElement() }
}

/**
 * TimerElement - timer wheel state
 */
class TimerElement {
    private val nextId = AtomicLong(1)
    val wheelSize: Int = 256

    private val scheduledTimers = mutableMapOf<TimerId, TimerEntry>()

    fun schedule(delayMs: Long, callback: () -> Unit): TimerId {
        val id = nextId.incrementAndGet().toULong()
        val deadline = System.currentTimeMillis() + delayMs
        scheduledTimers[id] = TimerEntry(id, deadline, callback)
        return id
    }

    fun cancel(id: TimerId): Boolean {
        return scheduledTimers.remove(id) != null
    }

    /**
     * Check and fire any expired timers
     */
    fun tick() {
        val now = System.currentTimeMillis()
        val expired = scheduledTimers.filter { it.value.deadline <= now }
        for ((id, entry) in expired) {
            scheduledTimers.remove(id)
            entry.callback()
        }
    }
}

/**
 * Timer entry
 */
class TimerEntry(
    val id: TimerId,
    val deadline: Long,
    val callback: () -> Unit
)
