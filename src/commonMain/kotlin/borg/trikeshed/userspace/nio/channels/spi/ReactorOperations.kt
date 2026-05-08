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
 */
interface ReactorOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ReactorOperations>
    override val key: CoroutineContext.Key<*> get() = Key

    fun register(fd: Int, interests: Set<Interest>, userData: Long = 0L)
    fun deregister(fd: Int)
    suspend fun poll(timeout: Duration = Duration.INFINITE): List<ReactorSignal>
}

data class ReactorSignal(
    val fd: Int,
    val ready: Set<Interest>,
    val userData: Long,
)
