@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.lib.*
import borg.trikeshed.context.*
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.cinterop.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeSource
import platform.posix.*

class PosixReactorOperations(val synchronization: Synchronization = Synchronization.MONITOR) : ReactorOperations {
    private val lock = synchronization.lock()
    private val fdInterest = mutableMapOf<Int, MutableSet<Interest>>()
    private val fdUserData = mutableMapOf<Int, Long>()
    private val waiters = mutableMapOf<PosixCompletionWait, Long>()
    private var generation = 0L

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) = synchronize(lock) {
        fdInterest.getOrPut(fd) { mutableSetOf() }.addAll(interests)
        fdUserData[fd] = userData
        changed()
    }

    override fun deregister(fd: Int) = synchronize(lock) {
        fdInterest.remove(fd); fdUserData.remove(fd)
        changed()
    }

    private fun changed() {
        generation++
        for ((waiter, admitted) in waiters) if (admitted != generation) waiter.wake()
    }

    private fun mask(interests: Set<Interest>): Int {
        var mask = 0
        if (Interest.READ in interests || Interest.ACCEPT in interests) mask = mask or POLLIN
        if (Interest.WRITE in interests || Interest.CONNECT in interests) mask = mask or POLLOUT
        return mask
    }

    /** Immediate observation under the interest lock; blocking waits never hold it or consume application bytes. */
    private fun collect(): List<ReactorSignal> = memScoped {
        val fds = fdInterest.keys.toList()
        if (fds.isEmpty()) return@memScoped emptyList()
        val pfds = allocArray<pollfd>(fds.size)
        for ((index, fd) in fds.withIndex()) {
            pfds[index].fd = fd
            pfds[index].events = mask(fdInterest.getValue(fd)).toShort()
            pfds[index].revents = 0
        }
        var n: Int
        do { n = platform.posix.poll(pfds, fds.size.convert(), 0) } while (n < 0 && errno == EINTR)
        check(n >= 0) { "reactor poll failed: $errno" }
        if (n == 0) return@memScoped emptyList()
        fds.indices.mapNotNull { i ->
            val pfd = pfds[i]
            val revents = pfd.revents.toInt()
            if (revents == 0) return@mapNotNull null
            val interests = fdInterest.getValue(pfd.fd)
            val ready = mutableSetOf<Interest>()
            if (revents and POLLIN != 0) {
                if (Interest.READ in interests) ready.add(Interest.READ)
                if (Interest.ACCEPT in interests) ready.add(Interest.ACCEPT)
            }
            if (revents and POLLOUT != 0) {
                if (Interest.WRITE in interests) ready.add(Interest.WRITE)
                if (Interest.CONNECT in interests) ready.add(Interest.CONNECT)
            }
            if (revents and (POLLERR or POLLHUP or POLLNVAL) != 0) {
                ready.add(Interest.ERROR)
                ready.addAll(interests)
            }
            if (ready.isEmpty()) null else ReactorSignal(pfd.fd, ready, fdUserData.getValue(pfd.fd))
        }
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val (signals, wait) = synchronize(lock) {
                val ready = collect()
                val expired = !timeout.isInfinite() && started.elapsedNow() >= timeout
                ready j if (ready.isNotEmpty() || expired) null else {
                    val snapshot = fdInterest.entries.map { it.key j mask(it.value) }
                    PosixCompletionWait(snapshot).also { waiters[it] = generation }
                }
            }
            if (wait == null) return signals
            val observed = try {
                if (timeout.isInfinite()) { wait.await(); true }
                else {
                    val remaining = timeout - started.elapsedNow()
                    remaining > Duration.ZERO && withTimeoutOrNull(remaining.inWholeMilliseconds.coerceAtLeast(1)) {
                        wait.await(); true
                    } == true
                }
            } finally {
                synchronize(lock) { waiters.remove(wait) }
                wait.close()
            }
            if (!observed) return emptyList()
        }
    }
}
