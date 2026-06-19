@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.reactor.Interest
import kotlinx.cinterop.*
import kotlin.time.Duration
import platform.posix.*

class PosixReactorOperations : ReactorOperations {

    private val fdInterest = mutableMapOf<Int, MutableSet<Interest>>()
    private val fdUserData = mutableMapOf<Int, Long>()

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        fdInterest.getOrPut(fd) { mutableSetOf() }.addAll(interests)
        fdUserData[fd] = userData
    }

    override fun deregister(fd: Int) { fdInterest.remove(fd); fdUserData.remove(fd) }

    override suspend fun poll(timeout: Duration): List<ReactorSignal> = memScoped {
        val fds = fdInterest.keys.toList()
        if (fds.isEmpty()) return@memScoped emptyList()
        val pfds = allocArray<pollfd>(fds.size)
        for ((index, fd) in fds.withIndex()) {
            var events: Short = 0
            val ints = fdInterest[fd] ?: continue
            if (Interest.READ in ints) events = (events.toInt() or POLLIN).toShort()
            if (Interest.WRITE in ints) events = (events.toInt() or POLLOUT).toShort()
            pfds[index].fd = fd; pfds[index].events = events
        }
        val timeoutMs = if (timeout.isInfinite()) -1 else timeout.inWholeMilliseconds.toInt()
        val n = poll(pfds, fds.size.convert(), timeoutMs)
        if (n <= 0) return@memScoped emptyList()
        (0 until fds.size).mapNotNull { i ->
            val pfd = pfds[i]
            val revents = pfd.revents.toInt()
            if (revents == 0) return@mapNotNull null
            val ready = mutableSetOf<Interest>()
            if ((revents and POLLIN) != 0) ready.add(Interest.READ)
            if ((revents and POLLOUT) != 0) ready.add(Interest.WRITE)
            if ((revents and (POLLERR or POLLHUP)) != 0) ready.add(Interest.ERROR)
            ReactorSignal(pfd.fd, ready, fdUserData[pfd.fd] ?: 0L)
        }
    }
}
