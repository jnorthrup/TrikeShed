@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.context.Synchronization
import borg.trikeshed.context.synchronize
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.Winsock
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.posix.*
import platform.windows.htonl
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * [ReactorOperations] over WSAPoll: READ/ACCEPT as POLLRDNORM and WRITE/CONNECT as POLLWRNORM, level-triggered as
 * poll(2) is. POLLERR/POLLHUP/POLLNVAL report ERROR plus every registered interest. WSAPoll takes its descriptor array
 * when it starts, so a registration change during a wait, or a cancelled wait, sends one byte to a loopback UDP socket
 * connected to itself; that socket is always in the array, and the wait returns to take the new set.
 */
class WsaPollReactorOperations(val synchronization: Synchronization = Synchronization.MONITOR) : ReactorOperations {
    val lock = synchronization.lock()
    val fdInterest = HashMap<Int, MutableSet<Interest>>()
    val fdUserData = HashMap<Int, Long>()
    /** 1 while a WSAPoll is in progress and has not been woken. */
    val waiting = AtomicInt(0)
    val wakeFd: Int = memScoped {
        check(Winsock.started)
        val s = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        check(s != INVALID_SOCKET) { "wake socket: ${WSAGetLastError()}" }
        val sa = alloc<sockaddr_in>().apply {
            sin_family = AF_INET.convert()
            sin_port = 0u
            sin_addr.S_un.S_addr = htonl(0x7f000001u)
        }
        val len = alloc<IntVar>().apply { value = sizeOf<sockaddr_in>().toInt() }
        check(bind(s, sa.ptr.reinterpret(), len.value) == 0) { "wake bind: ${WSAGetLastError()}" }
        check(getsockname(s, sa.ptr.reinterpret(), len.ptr) == 0) { "wake getsockname: ${WSAGetLastError()}" }
        check(connect(s, sa.ptr.reinterpret(), len.value) == 0) { "wake connect: ${WSAGetLastError()}" }
        val fd = s.toInt()
        check(Winsock.nonblocking(fd) == 0) { "wake nonblocking: ${WSAGetLastError()}" }
        fd
    }
    val one = nativeHeap.allocArray<ByteVar>(64)

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) {
        synchronize(lock) {
            fdInterest.getOrPut(fd) { mutableSetOf() }.addAll(interests)
            fdUserData[fd] = userData
        }
        wake()
    }

    override fun deregister(fd: Int) {
        val removed = synchronize(lock) { fdUserData.remove(fd); fdInterest.remove(fd) != null }
        if (removed) wake()
    }

    /** One byte to the wake socket, once per wait. */
    fun wake() {
        if (waiting.compareAndSet(1, 0)) send(wakeFd.toULong(), one, 1, 0)
    }

    fun events(interests: Set<Interest>): Int {
        var mask = 0
        if (Interest.READ in interests || Interest.ACCEPT in interests) mask = mask or POLLRDNORM
        if (Interest.WRITE in interests || Interest.CONNECT in interests) mask = mask or POLLWRNORM
        return mask
    }

    /** One WSAPoll over the wake socket and every registered descriptor, up to [timeout]; the ready ones. */
    fun collect(timeout: Duration): List<ReactorSignal> = memScoped {
        val (fds, pfds) = synchronize(lock) {
            val fds = fdInterest.keys.toIntArray()
            val pfds = allocArray<WSAPOLLFD>(fds.size + 1)
            pfds[0].fd = wakeFd.toULong(); pfds[0].events = POLLRDNORM.convert(); pfds[0].revents = 0
            for (i in fds.indices) {
                pfds[i + 1].fd = fds[i].toULong()
                pfds[i + 1].events = events(fdInterest.getValue(fds[i])).convert()
                pfds[i + 1].revents = 0
            }
            waiting.value = 1
            fds j pfds
        }
        val millis = if (timeout.isInfinite()) -1 else timeout.inWholeMilliseconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val n = WSAPoll(pfds, (fds.size + 1).convert(), millis)
        waiting.value = 0
        check(n >= 0) { "WSAPoll failed: ${WSAGetLastError()}" }
        if (n == 0) return@memScoped emptyList()
        if (pfds[0].revents.toInt() != 0) while (recv(wakeFd.toULong(), one, 64, 0) > 0) { }
        synchronize(lock) {
            fds.indices.mapNotNull { i ->
                val revents = pfds[i + 1].revents.toInt()
                val fd = fds[i]
                val interests = fdInterest[fd]
                if (revents == 0 || interests == null) return@mapNotNull null
                val ready = mutableSetOf<Interest>()
                if (revents and POLLRDNORM != 0) {
                    if (Interest.READ in interests) ready.add(Interest.READ)
                    if (Interest.ACCEPT in interests) ready.add(Interest.ACCEPT)
                }
                if (revents and POLLWRNORM != 0) {
                    if (Interest.WRITE in interests) ready.add(Interest.WRITE)
                    if (Interest.CONNECT in interests) ready.add(Interest.CONNECT)
                }
                if (revents and (POLLERR or POLLHUP or POLLNVAL) != 0) {
                    ready.add(Interest.ERROR)
                    ready.addAll(interests)
                }
                if (ready.isEmpty()) null else ReactorSignal(fd, ready, fdUserData.getValue(fd))
            }
        }
    }

    /** The dispatcher hosts the blocking WSAPoll only; cancellation wakes it. */
    override suspend fun poll(timeout: Duration): List<ReactorSignal> {
        val stopped = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { wake() }
                try {
                    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable {
                        val result = runCatching { collect(timeout) }
                        stopped.complete(Unit)
                        continuation.resumeWith(result)
                    })
                } catch (failure: Throwable) {
                    stopped.complete(Unit)
                    continuation.resumeWith(Result.failure(failure))
                }
            }
        } finally { withContext(NonCancellable) { stopped.await() } }
    }
}
