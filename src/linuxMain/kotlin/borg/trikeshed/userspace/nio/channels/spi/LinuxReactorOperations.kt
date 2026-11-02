@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlinx.cinterop.*
import platform.posix.POLLIN
import platform.posix.pollfd
import platform.posix.poll
import kotlin.time.Duration

/**
 * [PosixReactorOperations] with [park]: the owner blocks in poll(2) on the descriptors registered for reading, on its
 * own thread. [poll] is the POSIX reactor's: a helper thread and a wake pipe per wait, which a wake that happens
 * hundreds of times a second cannot afford. One owner.
 */
class LinuxReactorOperations : ParkingReactor {
    private val posix = PosixReactorOperations()
    private val watched = IntArray(MAX_WATCHED)
    private var count = 0
    private val pfds = nativeHeap.allocArray<pollfd>(MAX_WATCHED)

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        if (Interest.READ in interests || Interest.ACCEPT in interests) {
            var at = 0
            while (at < count && watched[at] != fd) at++
            if (at == count) {
                require(count < MAX_WATCHED) { "more than $MAX_WATCHED descriptors" }
                watched[count++] = fd
            }
        }
        posix.register(fd, interests, userData)
    }

    override fun deregister(fd: Int) {
        var at = 0
        while (at < count && watched[at] != fd) at++
        if (at < count) {
            count--
            watched[at] = watched[count]
        }
        posix.deregister(fd)
    }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> = posix.poll(timeout)

    override fun park(timeoutNanos: Long): Boolean {
        for (i in 0 until count) {
            val p = pfds[i]
            p.fd = watched[i]
            p.events = POLLIN.toShort()
            p.revents = 0
        }
        // Milliseconds, rounded up: a timer is never early. A signal ends the wait early with -1: the owner loop pumps and comes back.
        val millis = if (timeoutNanos < 0) -1 else minOf((timeoutNanos + 999_999L) / 1_000_000L, Int.MAX_VALUE.toLong()).toInt()
        if (poll(pfds, count.convert(), millis) <= 0) return false
        for (i in 0 until count) if (pfds[i].revents.toInt() != 0) return true
        return false
    }

    companion object {
        const val MAX_WATCHED = 8
    }
}
