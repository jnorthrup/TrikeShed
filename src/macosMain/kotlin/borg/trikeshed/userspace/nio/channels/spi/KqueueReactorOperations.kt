@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.context.*
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.darwin.*
import platform.posix.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * [ReactorOperations] over one kqueue: READ/ACCEPT as EVFILT_READ and WRITE/CONNECT as EVFILT_WRITE, level-triggered
 * (no EV_CLEAR) as poll(2) is, so readiness persists until the owner consumes it. EV_EOF or EV_ERROR reports ERROR plus
 * every registered interest, as POLLHUP/POLLERR do. A wait costs one kevent(2) whatever the number of descriptors and
 * returns only ready ones; registrations reach the kernel at once, so a waiting poll observes them without a wake.
 * EVFILT_USER ident 0 wakes a cancelled wait.
 */
class KqueueReactorOperations(val synchronization: Synchronization = Synchronization.MONITOR) : ReactorOperations {
    val lock = synchronization.lock()
    val kq: Int = kqueue().also { check(it >= 0) { "kqueue failed: $errno" } }
    val fdInterest = HashMap<Int, MutableSet<Interest>>()
    val fdUserData = HashMap<Int, Long>()
    val capacity = 4096
    val events = nativeHeap.allocArray<kevent>(capacity)

    init {
        change(0, EVFILT_USER, EV_ADD or EV_CLEAR, 0u)
    }

    /** One change; ENOENT (deleting an absent filter) is not an error. */
    fun change(ident: Int, filter: Int, flags: Int, fflags: UInt): Int = memScoped {
        val record = alloc<kevent>()
        record.ident = ident.convert()
        record.filter = filter.convert()
        record.flags = flags.convert()
        record.fflags = fflags
        record.data = 0
        record.udata = null
        val result = kevent(kq, record.ptr, 1, null, 0, null)
        if (result < 0 && errno != ENOENT) -errno else 0
    }

    override fun register(fd: Int, interests: Set<Interest>, userData: Long) = synchronize(lock) {
        val set = fdInterest.getOrPut(fd) { mutableSetOf() }
        set.addAll(interests)
        fdUserData[fd] = userData
        if (Interest.READ in set || Interest.ACCEPT in set) check(change(fd, EVFILT_READ, EV_ADD, 0u) == 0) { "kevent add read $fd: $errno" }
        if (Interest.WRITE in set || Interest.CONNECT in set) check(change(fd, EVFILT_WRITE, EV_ADD, 0u) == 0) { "kevent add write $fd: $errno" }
    }

    override fun deregister(fd: Int) = synchronize(lock) {
        val set = fdInterest.remove(fd) ?: return@synchronize
        fdUserData.remove(fd)
        // A closed descriptor already left the kqueue (EBADF, ENOENT): nothing to undo.
        if (Interest.READ in set || Interest.ACCEPT in set) change(fd, EVFILT_READ, EV_DELETE, 0u)
        if (Interest.WRITE in set || Interest.CONNECT in set) change(fd, EVFILT_WRITE, EV_DELETE, 0u)
    }

    fun wake() { change(0, EVFILT_USER, 0, NOTE_TRIGGER.convert()) }

    /** One kevent(2) wait up to [timeout]; the ready events, folded per descriptor. */
    fun collect(timeout: Duration): List<ReactorSignal> = memScoped {
        val wait = if (timeout.isInfinite()) null else alloc<timespec>().apply {
            val nanos = timeout.inWholeNanoseconds.coerceAtLeast(0)
            tv_sec = (nanos / 1_000_000_000L).convert()
            tv_nsec = (nanos % 1_000_000_000L).convert()
        }
        var n: Int
        do { n = kevent(kq, null, 0, events, capacity, wait?.ptr) } while (n < 0 && errno == EINTR)
        check(n >= 0) { "kevent wait failed: $errno" }
        synchronize(lock) {
            val folded = LinkedHashMap<Int, MutableSet<Interest>>()
            for (i in 0 until n) {
                val e = events[i]
                if (e.filter.toInt() == EVFILT_USER) continue
                val fd = e.ident.toInt()
                val interests = fdInterest[fd] ?: continue
                val ready = folded.getOrPut(fd) { mutableSetOf() }
                val flags = e.flags.toInt()
                if (e.filter.toInt() == EVFILT_READ) {
                    if (Interest.READ in interests) ready.add(Interest.READ)
                    if (Interest.ACCEPT in interests) ready.add(Interest.ACCEPT)
                } else if (e.filter.toInt() == EVFILT_WRITE) {
                    if (Interest.WRITE in interests) ready.add(Interest.WRITE)
                    if (Interest.CONNECT in interests) ready.add(Interest.CONNECT)
                }
                if (flags and (EV_EOF or EV_ERROR) != 0) {
                    ready.add(Interest.ERROR)
                    ready.addAll(interests)
                }
            }
            folded.entries.mapNotNull { (fd, ready) -> if (ready.isEmpty()) null else ReactorSignal(fd, ready, fdUserData.getValue(fd)) }
        }
    }

    /** The dispatcher hosts the blocking kevent(2) only; cancellation triggers the user event. */
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
