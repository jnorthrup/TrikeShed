/**
 * Port of /Users/jim/work/literbike/src/reactor/timer.rs
 *
 * Timer Wheel — O(1) Scheduling. Fail-fast timeout management for select reactor.
 */
package borg.literbike.reactor

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Mirrors Rust type alias: `pub type TimeoutCallback = Box<dyn FnOnce() + Send + 'static>`
 */
typealias TimeoutCallback = () -> Unit

/**
 * Mirrors Rust struct: `#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)] pub struct TimerId(pub u64)`
 */
@JvmInline
value class TimerId(val value: Long)

/**
 * Mirrors Rust struct: `pub struct Timeout`
 */
class Timeout(
    val id: TimerId,
    val expiresAt: TimeSource.Monotonic.ValueTimeMark,
    callback: TimeoutCallback,
) {
    var callback: TimeoutCallback? = callback
        private set

    override fun toString(): String =
        "Timeout(id=$id, expiresAt=$expiresAt, callback=<callback>)"
}

/**
 * Mirrors Rust struct: `#[derive(Debug, Clone, Default)] pub struct TimerStats`
 */
data class TimerStats(
    var timersCreated: Long = 0,
    var timersCancelled: Long = 0,
    var timersExpired: Long = 0,
    var activeCount: Int = 0,
)

/**
 * Mirrors Rust struct: `pub struct TimerWheel`
 *
 * Timer wheel — O(1) scheduling, O(n) expiration.
 */
class TimerWheel {
    private var nextId: Long = 0
    private val timeouts = mutableMapOf<TimerId, Timeout>()
    private val expirationQueue = ArrayDeque<Pair<TimeSource.Monotonic.ValueTimeMark, TimerId>>()
    private var stats = TimerStats()

    companion object {
        fun create(): TimerWheel = TimerWheel()
    }

    /**
     * Mirrors Rust: `pub fn schedule(&mut self, delay: Duration, callback: TimeoutCallback) -> TimerId`
     */
    fun schedule(delay: Duration, callback: TimeoutCallback): TimerId {
        val id = TimerId(nextId)
        nextId += 1

        val expiresAt = TimeSource.Monotonic.markNow() + delay
        val timeout = Timeout(id, expiresAt, callback)

        timeouts[id] = timeout
        insertSorted(expiresAt, id)

        stats.timersCreated += 1
        stats.activeCount = timeouts.size
        return id
    }

    /**
     * Mirrors Rust: `pub fn cancel(&mut self, timer_id: TimerId) -> bool`
     */
    fun cancel(timerId: TimerId): Boolean {
        val removed = timeouts.remove(timerId)
        if (removed != null) {
            expirationQueue.removeAll { it.second == timerId }
            stats.timersCancelled += 1
            stats.activeCount = timeouts.size
            return true
        }
        return false
    }

    /**
     * Mirrors Rust: `pub fn next_timeout(&self) -> Option<Duration>`
     */
    fun nextTimeout(): Duration? {
        val front = expirationQueue.firstOrNull() ?: return null
        val (expiresAt, _) = front
        val now = TimeSource.Monotonic.markNow()
        return if (expiresAt > now) (expiresAt - now) else Duration.ZERO
    }

    /**
     * Mirrors Rust: `pub fn take_expired(&mut self) -> Vec<TimeoutCallback>`
     */
    fun takeExpired(): List<TimeoutCallback> {
        val now = TimeSource.Monotonic.markNow()
        val callbacks = mutableListOf<TimeoutCallback>()

        while (expirationQueue.isNotEmpty()) {
            val (expiresAt, timerId) = expirationQueue.first()
            if (expiresAt > now) break
            expirationQueue.removeFirst()

            val timeout = timeouts.remove(timerId)
            if (timeout != null) {
                val cb = timeout.callback
                if (cb != null) {
                    callbacks.add(cb)
                    stats.timersExpired += 1
                }
            }
        }

        stats.activeCount = timeouts.size
        return callbacks
    }

    /**
     * Mirrors Rust: `pub fn active_count(&self) -> usize`
     */
    fun activeCount(): Int = timeouts.size

    /**
     * Mirrors Rust: `pub fn stats(&self) -> &TimerStats`
     */
    fun stats(): TimerStats = stats

    private fun insertSorted(expiresAt: TimeSource.Monotonic.ValueTimeMark, timerId: TimerId) {
        val pos = expirationQueue.indexOfFirst { (time, _) -> time > expiresAt }
            .takeIf { it >= 0 } ?: expirationQueue.size
        expirationQueue.add(pos, expiresAt to timerId)
    }
}
