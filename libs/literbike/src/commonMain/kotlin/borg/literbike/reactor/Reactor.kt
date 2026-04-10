/**
 * Port of /Users/jim/work/literbike/src/reactor/reactor.rs
 *
 * Core reactor event loop with registration, readiness dispatch, and timers.
 */
package borg.literbike.reactor

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Mirrors Rust struct: `struct PendingRegistration`
 */
internal data class PendingRegistration(
    val channel: SelectableChannel,
    val interests: InterestSet,
    val handler: EventHandler,
)

/**
 * Mirrors Rust struct: `#[derive(Debug, Clone, Default)] pub struct ReactorStats`
 */
data class ReactorStats(
    var registrationsApplied: Long = 0,
    var selectCalls: Long = 0,
    var dispatchCallbacks: Long = 0,
    var timerCallbacks: Long = 0,
    var handlerErrors: Long = 0,
    var shutdowns: Long = 0,
)

/**
 * Mirrors Rust struct: `#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)] pub struct ReactorTickResult`
 */
data class ReactorTickResult(
    val registrationsApplied: Int = 0,
    val readyEvents: Int = 0,
    val handlerCallbacks: Int = 0,
    val timerCallbacks: Int = 0,
) {
    companion object {
        val Default = ReactorTickResult()
    }
}

/**
 * Mirrors Rust struct: `pub struct Reactor<S: SelectorBackend = ManualSelector>`
 */
class Reactor<S : SelectorBackend> private constructor(
    private val selector: S,
    private val timerWheel: TimerWheel,
    private val pendingRegistrations: ArrayDeque<PendingRegistration>,
    private val handlers: MutableMap<Int, EventHandler>,
    private val channels: MutableMap<Int, SelectableChannel>,
    private val defaultSelectTimeout: Duration,
    private var running: Boolean,
    private val stats: ReactorStats,
) {

    constructor(selector: S, config: ReactorConfig) : this(
        selector = selector,
        timerWheel = TimerWheel(),
        pendingRegistrations = ArrayDeque(),
        handlers = mutableMapOf(),
        channels = mutableMapOf(),
        defaultSelectTimeout = config.selectTimeoutMs.coerceAtLeast(1).milliseconds,
        running = true,
        stats = ReactorStats(),
    )

    companion object {
        fun withDefaultConfig(selector: ManualSelector): Reactor<ManualSelector> =
            Reactor(selector, ReactorConfig())

        fun manual(config: ReactorConfig): Reactor<ManualSelector> =
            Reactor(ManualSelector(), config)
    }

    fun isActive(): Boolean = running

    fun stats(): ReactorStats = stats

    fun registeredChannels(): Int = channels.size

    fun scheduleTimeout(delay: Duration, callback: TimeoutCallback): TimerId =
        timerWheel.schedule(delay, callback)

    fun cancelTimeout(timerId: TimerId): Boolean =
        timerWheel.cancel(timerId)

    /**
     * Mirrors Rust: `pub fn register_channel<C, H>(&mut self, channel: C, interests: InterestSet, handler: H) -> io::Result<RawFd>`
     */
    fun registerChannel(
        channel: SelectableChannel,
        interests: InterestSet,
        handler: EventHandler,
    ): Result<Int> {
        val fd = channel.rawFd()
        if (fd < 0) {
            return Result.failure(IOException("negative fd"))
        }
        pendingRegistrations.addLast(
            PendingRegistration(channel, interests, handler)
        )
        selector.wakeup()
        return Result.success(fd)
    }

    /**
     * Mirrors Rust: `pub fn run_once(&mut self) -> io::Result<ReactorTickResult>`
     */
    fun runOnce(): Result<ReactorTickResult> {
        if (!running) {
            return Result.success(ReactorTickResult.Default)
        }

        val registrationsApplied = applyPendingRegistrations()
        val timeout = computePollTimeout()

        stats.selectCalls += 1
        val events = selector.select(timeout).getOrThrow()

        var callbacks = 0
        for (event in events) {
            callbacks += dispatchEvent(event)
        }

        val timerCallbacks = runExpiredTimers()

        return Result.success(
            ReactorTickResult(
                registrationsApplied = registrationsApplied,
                readyEvents = events.size,
                handlerCallbacks = callbacks,
                timerCallbacks = timerCallbacks,
            )
        )
    }

    /**
     * Mirrors Rust: `pub fn shutdown(&mut self) -> io::Result<()>`
     */
    fun shutdown(): Result<Unit> {
        if (!running) {
            return Result.success(Unit)
        }
        running = false
        stats.shutdowns += 1

        while (pendingRegistrations.isNotEmpty()) {
            val pending = pendingRegistrations.removeFirst()
            pending.channel.close()
        }

        val drained = channels.entries.toList()
        channels.clear()
        for (entry in drained) {
            entry.value.close()
        }
        handlers.clear()

        return selector.close()
    }

    fun selector(): S = selector

    // ========================================================================
    // Private methods
    // ========================================================================

    private fun computePollTimeout(): Duration {
        return timerWheel.nextTimeout()?.let { next ->
            if (next < defaultSelectTimeout) next else defaultSelectTimeout
        } ?: defaultSelectTimeout
    }

    private fun applyPendingRegistrations(): Int {
        var applied = 0
        while (pendingRegistrations.isNotEmpty()) {
            val pending = pendingRegistrations.removeFirst()
            val fd = pending.channel.rawFd()
            val result = selector.register(fd, pending.interests)
            if (result.isSuccess) {
                channels[fd] = pending.channel
                handlers[fd] = pending.handler
                stats.registrationsApplied += 1
                applied += 1
            } else {
                val ex = result.exceptionOrNull()
                val kind = IOException(ex?.message ?: "registration failed", ex)
                pending.handler.onError(fd, kind)
                stats.handlerErrors += 1
                pending.channel.close()
            }
        }
        return applied
    }

    private fun dispatchEvent(event: ReadyEvent): Int {
        val handler = handlers[event.fd] ?: return 0

        var callbacks = 0
        if (event.ready.contains(InterestSet.READ) || event.ready.contains(InterestSet.ACCEPT)) {
            handler.onReadable(event.fd)
            callbacks += 1
            stats.dispatchCallbacks += 1
        }
        if (event.ready.contains(InterestSet.WRITE) || event.ready.contains(InterestSet.CONNECT)) {
            handler.onWritable(event.fd)
            callbacks += 1
            stats.dispatchCallbacks += 1
        }
        if (event.ready.contains(InterestSet.ERROR)) {
            handler.onError(event.fd, IOException("reactor readiness error"))
            callbacks += 1
            stats.dispatchCallbacks += 1
            stats.handlerErrors += 1
        }
        return callbacks
    }

    private fun runExpiredTimers(): Int {
        val callbacks = timerWheel.takeExpired()
        val count = callbacks.size
        for (cb in callbacks) {
            cb()
        }
        stats.timerCallbacks += count
        return count
    }
}

/**
 * Extension for ManualSelector-specific convenience methods.
 * Mirrors Rust: `impl Reactor<ManualSelector>` block.
 */
fun Reactor<ManualSelector>.injectReady(fd: Int, ready: InterestSet) {
    selector().injectReady(fd, ready)
}
