package borg.trikeshed.userspace.nio.channels.spi

import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import borg.trikeshed.userspace.reactor.Interest

/**
 * Platform poll/reactor abstraction — fills the NIO async-file-signal gap.
 *
 * [java.nio.channels.FileChannel] cannot register with [java.nio.channels.Selector].
 * [ReactorOperations] bridges this: kqueue (macOS), io_uring (Linux), libuv (Node.js),
 * poll(2) (POSIX fallback).
 *
 * Implementations with a synchronization option default to monitored state access.
 * NONE requires serialized registration, deregistration, polling and lifecycle access
 * by the owning coroutine context, including on multicore hosts. It does not remove
 * OS waiter/cancellation resource locks or select behavior from the CPU count.
 */
interface ReactorOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ReactorOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun register(fd: Int, interests: Set<Interest>, userData: Long = 0L)
    fun deregister(fd: Int)
    suspend fun poll(timeout: Duration = Duration.INFINITE): List<ReactorSignal>
}

/**
 * A reactor whose owner may block in it. [park] waits on the calling thread, with no coroutine, no helper thread and
 * no allocation, until a registered descriptor is readable or [timeoutNanos] has passed (negative: no timeout), and
 * says whether one is readable. While it waits, no other coroutine of that thread runs, so only a thread that owns
 * nothing else (a server's owner loop) uses it; every other caller goes through [poll].
 */
interface ParkingReactor : ReactorOperations {
    fun park(timeoutNanos: Long): Boolean
}

data class ReactorSignal(
    val fd: Int,
    val ready: Set<Interest>,
    val userData: Long,
)
