/**
 * Port of /Users/jim/work/literbike/src/reactor/selector.rs
 *
 * Portable selector abstraction.
 *
 * The baseline implementation (`ManualSelector`) is deterministic and testable:
 * callers inject readiness events explicitly rather than relying on OS polling.
 */
package borg.literbike.reactor

import java.io.IOException
import kotlin.time.Duration

/**
 * Mirrors Rust struct: `#[derive(Debug, Clone, Copy, PartialEq, Eq)] pub struct ReadyEvent`
 */
data class ReadyEvent(
    val fd: Int,
    val ready: InterestSet,
) {
    companion object {
        fun of(fd: Int, ready: InterestSet): ReadyEvent = ReadyEvent(fd, ready)
    }
}

/**
 * Mirrors Rust trait: `pub trait SelectorBackend`
 */
interface SelectorBackend {
    fun register(fd: Int, interests: InterestSet): Result<Unit>
    fun reregister(fd: Int, interests: InterestSet): Result<Unit>
    fun unregister(fd: Int): Result<Unit>
    fun select(timeout: Duration?): Result<List<ReadyEvent>>
    fun wakeup()
    fun close(): Result<Unit>
    fun isClosed(): Boolean
}

/**
 * Mirrors Rust struct: `#[derive(Debug, Default)] pub struct ManualSelector`
 */
class ManualSelector private constructor(
    private val registrations: MutableMap<Int, InterestSet>,
    private val readyQueue: ArrayDeque<ReadyEvent>,
    private var closed: Boolean,
    var lastTimeout: Duration?,
    private var wakeupCount: Long,
    private var sleepOnEmpty: Boolean,
) : SelectorBackend {

    constructor() : this(
        registrations = mutableMapOf(),
        readyQueue = ArrayDeque(),
        closed = false,
        lastTimeout = null,
        wakeupCount = 0,
        sleepOnEmpty = false,
    )

    fun withSleepOnEmpty(enabled: Boolean): ManualSelector {
        this.sleepOnEmpty = enabled
        return this
    }

    fun injectReady(fd: Int, ready: InterestSet) {
        readyQueue.addLast(ReadyEvent(fd, ready))
    }

    fun registeredCount(): Int = registrations.size

    fun wakeupCount(): Long = wakeupCount

    override fun register(fd: Int, interests: InterestSet): Result<Unit> {
        if (closed) {
            return Result.failure(IOException("selector is closed"))
        }
        if (registrations.containsKey(fd)) {
            return Result.failure(IOException("fd $fd already registered"))
        }
        registrations[fd] = interests
        return Result.success(Unit)
    }

    override fun reregister(fd: Int, interests: InterestSet): Result<Unit> {
        if (closed) {
            return Result.failure(IOException("selector is closed"))
        }
        val slot = registrations[fd]
            ?: return Result.failure(IOException("fd $fd not registered"))
        registrations[fd] = interests
        return Result.success(Unit)
    }

    override fun unregister(fd: Int): Result<Unit> {
        registrations.remove(fd)
        return Result.success(Unit)
    }

    override fun select(timeout: Duration?): Result<List<ReadyEvent>> {
        if (closed) {
            return Result.success(emptyList())
        }
        lastTimeout = timeout

        if (sleepOnEmpty && readyQueue.isEmpty()) {
            val delay = timeout
            if (delay != null && delay != Duration.ZERO) {
                Thread.sleep(delay.inWholeMilliseconds)
            }
        }

        val out = mutableListOf<ReadyEvent>()
        while (readyQueue.size > 0) {
            val event = readyQueue.removeFirst()
            val interests = registrations[event.fd] ?: continue
            val filtered = event.ready and interests
            if (!filtered.isEmpty()) {
                out.add(ReadyEvent(event.fd, filtered))
            }
        }
        return Result.success(out)
    }

    override fun wakeup() {
        wakeupCount += 1
    }

    override fun close(): Result<Unit> {
        closed = true
        readyQueue.clear()
        registrations.clear()
        return Result.success(Unit)
    }

    override fun isClosed(): Boolean = closed
}
