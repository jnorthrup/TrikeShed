package borg.trikeshed.userspace

import borg.trikeshed.collections.LongTable
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

private const val IN = 0x1
private const val PRI = 0x2
private const val OUT = 0x4
private const val ERR = 0x8
private const val HUP = 0x10
private const val NVAL = 0x20
private const val EPOLL_CTL_ADD = 1
private const val EPOLL_CTL_DEL = 2
private const val EPOLL_CTL_MOD = 3
private const val EPOLLONESHOT = 1 shl 30
private const val EVFILT_READ = -1
private const val EVFILT_WRITE = -2
private const val EV_ADD = 0x1
private const val EV_DELETE = 0x2
private const val EV_ONESHOT = 0x10
private const val EV_ERROR = 0x4000
private const val EV_EOF = 0x8000
private const val MIN_EVENTS = 64
private const val MAX_EVENTS = 4096
/** A kernel-set wait cannot see a descriptor closed outside the ring; such watches end within this period. */
private const val SWEEP_NANOS = 250_000_000L

/**
 * Watched socket descriptors above which [JvmReadiness] moves to the kernel interest set. Below half of it, it
 * returns to poll(2). The trikeshed.uring.readiness.crossover property or TRIKESHED_URING_READINESS_CROSSOVER
 * overrides it; 0 always uses the kernel set, [Int.MAX_VALUE] never does.
 */
internal const val READINESS_CROSSOVER: Int = 64

internal fun readinessCrossover(): Int =
    (System.getProperty("trikeshed.uring.readiness.crossover") ?: System.getenv("TRIKESHED_URING_READINESS_CROSSOVER"))
        ?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: READINESS_CROSSOVER

/**
 * Readiness of the JVM emulated ring's deferred watches: POLL_ADD and in-progress CONNECT.
 *
 * Each watch completes once, with the revents its mask (plus POLLERR, POLLHUP, POLLNVAL) admits. Two mechanisms
 * serve that contract. poll(2) scans every watched descriptor on every wait and costs nothing per watch; the kernel
 * interest set (epoll on Linux, kqueue on Darwin) costs one control call per one-shot arm and reports only ready
 * descriptors. With N watched sockets the scan grows with N while the arms grow with readiness, so above
 * [crossover] sockets the kernel set serves them, and below half of it poll(2) does again; the gap keeps a set that
 * hovers at the boundary from migrating on every wait. Descriptors that are not sockets (files, pipes) stay with
 * poll(2), whose answer for them the kernel sets do not give; the kernel set's own descriptor then joins that poll.
 *
 * Callers serialize every call (the backend's monitor); nothing here locks.
 */
internal class JvmReadiness(val crossover: Int = readinessCrossover()) {
    /** The watches on one operating-system descriptor. */
    internal class Interest(val fd: Int, val descriptor: JvmDescriptor, val socket: Boolean) {
        var tokens = LongArray(2)
        var masks = IntArray(2)
        var connects = BooleanArray(2)
        var count = 0
        var connectCount = 0
        /** Mask armed in the kernel set of [generation]; one-shot delivery clears it. */
        var armed = 0
        var generation = -1
        /** Index in the active list; -1 while no watch is held. */
        var slot = -1

        fun union(): Int {
            var mask = 0
            for (i in 0 until count) mask = mask or masks[i]
            return mask
        }

        fun index(token: Long): Int {
            for (i in 0 until count) if (tokens[i] == token) return i
            return -1
        }

        fun drop(i: Int) {
            if (connects[i]) connectCount--
            count--
            tokens[i] = tokens[count]; masks[i] = masks[count]; connects[i] = connects[count]
        }
    }

    /** Where readiness lands: the backend turns it into CQEs. */
    interface Sink {
        /** [revents] reached [token]'s watch; false keeps the watch (a CONNECT still in progress). */
        fun ready(token: Long, revents: Int, connect: Boolean, descriptor: JvmDescriptor): Boolean
        /** [token]'s watch ended without readiness: [res] is its CQE result. */
        fun ended(token: Long, res: Int, connect: Boolean)
    }

    private val linux = System.getProperty("os.name").lowercase().contains("linux")
    private val darwin = System.getProperty("os.name").lowercase().contains("mac")
    private val packed = System.getProperty("os.arch").let { it == "amd64" || it == "x86_64" }
    private val eventBytes = if (darwin) 32L else if (packed) 12L else 16L
    private val kernelCapable = linux || darwin
    private var byFd = arrayOfNulls<Interest>(64)
    private val byToken = LongTable<Interest>()
    private var sockets = arrayOfNulls<Interest>(16)
    private var others = arrayOfNulls<Interest>(4)
    /** Socket descriptors holding watches: the count the crossover compares. */
    var watchedSockets = 0
        private set
    private var otherCount = 0
    var kernel = false
        private set
    private var kfd = -1
    private var generation = 0
    private val arena = Arena.ofAuto()
    private var records: MemorySegment = arena.allocate(8L * 16, 8)
    private var recordInterests = arrayOfNulls<Interest>(16)
    private var recordCount = 0
    private var dirty = true
    private var events: MemorySegment = MemorySegment.NULL
    private var maxEvents = MIN_EVENTS
    private var quietWaits = 0
    private val change: MemorySegment = arena.allocate(64, 8)
    private val timespec: MemorySegment = arena.allocate(16, 8)
    private var sweptAt = System.nanoTime()

    var waits = 0L
        private set
    var delivered = 0L
        private set
    var controls = 0L
        private set
    var switches = 0L
        private set
    var scanned = 0L
        private set

    val watches: Int get() = byToken.size

    fun watching(token: Long): Boolean = token in byToken

    /** -1 when [token] holds no watch, 1 for an in-progress CONNECT, 0 for a POLL_ADD. */
    fun kind(token: Long): Int {
        val interest = byToken[token] ?: return -1
        return if (interest.connects[interest.index(token)]) 1 else 0
    }

    fun connectPending(descriptor: JvmDescriptor): Boolean {
        val fd = osFd(descriptor)
        if (fd < 0 || fd >= byFd.size) return false
        val interest = byFd[fd] ?: return false
        return interest.descriptor === descriptor && interest.connectCount > 0
    }

    fun watch(token: Long, descriptor: JvmDescriptor, fd: Int, mask: Int, connect: Boolean, sink: Sink) {
        val interest = interest(descriptor, fd, sink)
        if (interest.count == interest.tokens.size) {
            val grown = interest.count * 2
            interest.tokens = interest.tokens.copyOf(grown)
            interest.masks = interest.masks.copyOf(grown)
            interest.connects = interest.connects.copyOf(grown)
        }
        interest.tokens[interest.count] = token
        interest.masks[interest.count] = mask
        interest.connects[interest.count] = connect
        interest.count++
        if (connect) interest.connectCount++
        byToken.put(token, interest)
        if (interest.slot < 0) activate(interest)
        if (kernel && interest.socket) arm(interest, sink) else dirty = true
        switchIfCrossed(sink)
    }

    fun unwatch(token: Long, sink: Sink): Boolean {
        val interest = byToken.remove(token) ?: return false
        interest.drop(interest.index(token))
        settle(interest, sink)
        switchIfCrossed(sink)
        return true
    }

    /** [descriptor] is being closed by its owner: its watches end with EBADF now, not at the next sweep. */
    fun closing(descriptor: JvmDescriptor, sink: Sink) {
        val fd = osFd(descriptor)
        if (fd < 0 || fd >= byFd.size) return
        val interest = byFd[fd] ?: return
        if (interest.descriptor === descriptor) release(interest, sink, -9)
    }

    /** Ends every watch with [res]: cancellation during drain. */
    fun endAll(sink: Sink, res: Int) {
        while (watchedSockets > 0) release(sockets[watchedSockets - 1]!!, sink, res)
        while (otherCount > 0) release(others[otherCount - 1]!!, sink, res)
    }

    /** One wait of at most [timeoutMillis] (0 polls, negative blocks); readiness reaches [sink]. */
    fun wait(timeoutMillis: Int, sink: Sink) {
        waits++
        if (kernel) {
            val now = System.nanoTime()
            if (now - sweptAt > SWEEP_NANOS) {
                sweptAt = now
                scanned += watchedSockets
                var k = watchedSockets - 1
                while (k >= 0) {
                    val interest = sockets[k]!!
                    if (!interest.descriptor.isOpen()) release(interest, sink, -9)
                    k = minOf(k, watchedSockets) - 1
                }
            }
            if (otherCount == 0) kernelWait(timeoutMillis, sink) else pollWait(timeoutMillis, sink)
        } else pollWait(timeoutMillis, sink)
        switchIfCrossed(sink)
    }

    fun counters(): Map<String, Long> = linkedMapOf(
        "readiness.kernel" to (if (kernel) 1L else 0L), "readiness.crossover" to crossover.toLong(),
        "readiness.watches" to watches.toLong(), "readiness.sockets" to watchedSockets.toLong(),
        "readiness.waits" to waits, "readiness.delivered" to delivered, "readiness.controls" to controls,
        "readiness.switches" to switches, "readiness.scanned" to scanned, "readiness.maxEvents" to maxEvents.toLong(),
    )

    fun close() {
        if (kfd >= 0) JvmSocketSyscalls.close(kfd)
        kfd = -1
        kernel = false
    }

    private fun osFd(descriptor: JvmDescriptor): Int = when (descriptor) {
        is JvmSocketDescriptor -> descriptor.fd
        is JvmNativeDescriptor -> descriptor.fd
        else -> -1
    }

    private fun interest(descriptor: JvmDescriptor, fd: Int, sink: Sink): Interest {
        if (fd >= byFd.size) byFd = byFd.copyOf(maxOf(fd + 1, byFd.size * 2))
        val held = byFd[fd]
        if (held != null && held.descriptor === descriptor) return held
        // The number was closed and reused without this table seeing the close: the old watches cannot fire again.
        if (held != null) release(held, sink, -9)
        return Interest(fd, descriptor, descriptor is JvmSocketDescriptor).also { byFd[fd] = it }
    }

    private fun activate(interest: Interest) {
        if (interest.socket) {
            if (watchedSockets == sockets.size) sockets = sockets.copyOf(watchedSockets * 2)
            interest.slot = watchedSockets
            sockets[watchedSockets++] = interest
        } else {
            if (otherCount == others.size) others = others.copyOf(otherCount * 2)
            interest.slot = otherCount
            others[otherCount++] = interest
        }
        dirty = true
    }

    private fun deactivate(interest: Interest) {
        val slot = interest.slot
        if (interest.socket) {
            val last = sockets[--watchedSockets]!!
            sockets[slot] = last; last.slot = slot; sockets[watchedSockets] = null
        } else {
            val last = others[--otherCount]!!
            others[slot] = last; last.slot = slot; others[otherCount] = null
        }
        interest.slot = -1
        dirty = true
    }

    private fun release(interest: Interest, sink: Sink, res: Int) {
        while (interest.count > 0) {
            val i = interest.count - 1
            val token = interest.tokens[i]
            val connect = interest.connects[i]
            interest.drop(i)
            byToken.remove(token)
            sink.ended(token, res, connect)
        }
        if (interest.slot >= 0) deactivate(interest)
        if (byFd[interest.fd] === interest) byFd[interest.fd] = null
    }

    private fun settle(interest: Interest, sink: Sink) {
        if (interest.count == 0) {
            // A kernel registration left armed fires once more at most and is ignored: no call spent disarming it.
            if (interest.slot >= 0) deactivate(interest)
        } else if (kernel && interest.socket) arm(interest, sink)
        else dirty = true
    }

    private fun switchIfCrossed(sink: Sink) {
        if (!kernel) {
            if (kernelCapable && watchedSockets > crossover) toKernel(sink)
        } else if (watchedSockets < crossover / 2) toPoll()
    }

    private fun toKernel(sink: Sink) {
        if (kfd < 0) {
            controls++
            val opened = if (linux) JvmSocketSyscalls.epollCreate() else JvmSocketSyscalls.kqueueCreate()
            if (opened < 0) return
            kfd = opened
            generation++
        }
        kernel = true
        switches++
        var k = 0
        while (k < watchedSockets) {
            val interest = sockets[k]!!
            arm(interest, sink)
            if (k < watchedSockets && sockets[k] === interest) k++
        }
        dirty = true
    }

    private fun toPoll() {
        // Closing the set drops every registration in one call; generation makes each interest re-register later.
        if (kfd >= 0) JvmSocketSyscalls.close(kfd)
        kfd = -1
        kernel = false
        switches++
        dirty = true
    }

    private fun arm(interest: Interest, sink: Sink) {
        val union = interest.union()
        val want = if (linux) union and (IN or PRI or OUT)
            else (if (union and (IN or PRI) != 0) IN else 0) or (union and OUT)
        val current = if (interest.generation == generation) interest.armed else 0
        if (want == current) return
        val result = if (linux) epollArm(interest, want) else kqueueArm(interest, want, current)
        if (result < 0) release(interest, sink, result)
    }

    private fun epollArm(interest: Interest, want: Int): Int {
        val registered = interest.generation == generation
        controls++
        if (want == 0) {
            val removed = JvmSocketSyscalls.epollCtl(kfd, EPOLL_CTL_DEL, interest.fd, 0, change, packed)
            interest.generation = -1
            interest.armed = 0
            return if (removed == -2 || removed == -9) 0 else removed
        }
        val events = want or EPOLLONESHOT
        var result = JvmSocketSyscalls.epollCtl(kfd, if (registered) EPOLL_CTL_MOD else EPOLL_CTL_ADD, interest.fd, events, change, packed)
        if (result == -2 && registered) {
            controls++
            result = JvmSocketSyscalls.epollCtl(kfd, EPOLL_CTL_ADD, interest.fd, events, change, packed)
        } else if (result == -17 && !registered) {
            controls++
            result = JvmSocketSyscalls.epollCtl(kfd, EPOLL_CTL_MOD, interest.fd, events, change, packed)
        }
        if (result < 0) return result
        interest.generation = generation
        interest.armed = want
        return 0
    }

    private fun kqueueArm(interest: Interest, want: Int, current: Int): Int {
        var n = 0
        fun put(filter: Int, flags: Int) {
            val at = 32L * n++
            change.set(ValueLayout.JAVA_LONG, at, interest.fd.toLong())
            change.set(ValueLayout.JAVA_SHORT, at + 8, filter.toShort())
            change.set(ValueLayout.JAVA_SHORT, at + 10, flags.toShort())
            change.set(ValueLayout.JAVA_INT, at + 12, 0)
            change.set(ValueLayout.JAVA_LONG, at + 16, 0L)
            change.set(ValueLayout.JAVA_LONG, at + 24, 0L)
        }
        if (want and IN != 0 && current and IN == 0) put(EVFILT_READ, EV_ADD or EV_ONESHOT)
        if (want and IN == 0 && current and IN != 0) put(EVFILT_READ, EV_DELETE)
        if (want and OUT != 0 && current and OUT == 0) put(EVFILT_WRITE, EV_ADD or EV_ONESHOT)
        if (want and OUT == 0 && current and OUT != 0) put(EVFILT_WRITE, EV_DELETE)
        if (n == 0) return 0
        controls++
        val result = JvmSocketSyscalls.kevent(kfd, change, n, MemorySegment.NULL, 0, MemorySegment.NULL)
        if (result < 0 && result != -2) return result
        interest.generation = generation
        interest.armed = want
        return 0
    }

    private fun pollWait(timeoutMillis: Int, sink: Sink) {
        // A descriptor closed outside the ring ends its watches before the call, as poll(2) servicing always did.
        if (!kernel) {
            var k = watchedSockets - 1
            while (k >= 0) {
                val interest = sockets[k]!!
                if (!interest.descriptor.isOpen()) release(interest, sink, -9)
                k = minOf(k, watchedSockets) - 1
            }
        }
        var k = otherCount - 1
        while (k >= 0) {
            val interest = others[k]!!
            if (!interest.descriptor.isOpen()) release(interest, sink, -9)
            k = minOf(k, otherCount) - 1
        }
        if (dirty) rebuild()
        if (recordCount == 0) return
        val ready = JvmSocketSyscalls.pollRecords(records, recordCount, timeoutMillis)
        scanned += recordCount
        if (ready <= 0) return
        for (r in 0 until recordCount) {
            val revents = records.get(ValueLayout.JAVA_SHORT, r * 8L + 6).toInt() and 0xffff
            if (revents == 0) continue
            val interest = recordInterests[r]
            if (interest == null) kernelWait(0, sink)
            else if (interest.slot >= 0) dispatch(interest, revents, sink)
        }
    }

    private fun rebuild() {
        val total = (if (kernel) 1 else watchedSockets) + otherCount
        if (records.byteSize() < total * 8L) {
            var size = records.byteSize() / 8
            while (size < total) size *= 2
            records = arena.allocate(size * 8, 8)
            recordInterests = arrayOfNulls(size.toInt())
        }
        recordCount = 0
        fun put(fd: Int, mask: Int, interest: Interest?) {
            val at = recordCount * 8L
            records.set(ValueLayout.JAVA_INT, at, fd)
            records.set(ValueLayout.JAVA_SHORT, at + 4, (mask and 0x3f).toShort())
            records.set(ValueLayout.JAVA_SHORT, at + 6, 0)
            recordInterests[recordCount++] = interest
        }
        if (!kernel) for (k in 0 until watchedSockets) sockets[k]!!.let { put(it.fd, it.union(), it) }
        for (k in 0 until otherCount) others[k]!!.let { put(it.fd, it.union(), it) }
        if (kernel && kfd >= 0) put(kfd, IN, null)
        for (r in recordCount until recordInterests.size) recordInterests[r] = null
        dirty = false
    }

    private fun kernelWait(timeoutMillis: Int, sink: Sink) {
        if (kfd < 0) return
        if (events.byteSize() < maxEvents * eventBytes) events = arena.allocate(maxEvents * eventBytes, 8)
        val count = if (linux) JvmSocketSyscalls.epollWait(kfd, events, maxEvents, timeoutMillis)
        else {
            val limit = if (timeoutMillis < 0) MemorySegment.NULL else timespec.also {
                it.set(ValueLayout.JAVA_LONG, 0, timeoutMillis / 1000L)
                it.set(ValueLayout.JAVA_LONG, 8, (timeoutMillis % 1000) * 1_000_000L)
            }
            JvmSocketSyscalls.kevent(kfd, MemorySegment.NULL, 0, events, maxEvents, limit)
        }
        if (count <= 0) {
            adapt(0)
            return
        }
        for (e in 0 until count) {
            val at = e * eventBytes
            val fd: Int
            val revents: Int
            if (linux) {
                revents = events.get(ValueLayout.JAVA_INT, at) and 0x1f
                fd = events.get(ValueLayout.JAVA_INT, at + if (packed) 4 else 8)
            } else {
                fd = events.get(ValueLayout.JAVA_LONG, at).toInt()
                val filter = events.get(ValueLayout.JAVA_SHORT, at + 8).toInt()
                val flags = events.get(ValueLayout.JAVA_SHORT, at + 10).toInt() and 0xffff
                revents = (if (filter == EVFILT_READ) IN else OUT) or
                    (if (flags and EV_EOF != 0) HUP else 0) or (if (flags and EV_ERROR != 0) ERR else 0)
            }
            if (fd < 0 || fd >= byFd.size) continue
            val interest = byFd[fd] ?: continue
            if (interest.generation != generation) continue
            interest.armed = if (linux) 0 else interest.armed and (if (revents and IN != 0) IN else OUT).inv()
            if (interest.slot >= 0) dispatch(interest, revents, sink)
        }
        adapt(count)
    }

    /** Event batch under completion pressure: doubles when a wait fills it, halves after 64 waits under 1/8 full. */
    private fun adapt(count: Int) {
        if (count == maxEvents && maxEvents < MAX_EVENTS) {
            maxEvents *= 2
            quietWaits = 0
        } else if (count < maxEvents / 8 && maxEvents > MIN_EVENTS) {
            if (++quietWaits >= 64) {
                maxEvents /= 2
                quietWaits = 0
            }
        } else quietWaits = 0
    }

    private fun dispatch(interest: Interest, revents: Int, sink: Sink) {
        delivered++
        var i = 0
        while (i < interest.count) {
            val hit = revents and (interest.masks[i] or ERR or HUP or NVAL)
            if (hit == 0) {
                i++
                continue
            }
            val token = interest.tokens[i]
            if (sink.ready(token, hit, interest.connects[i], interest.descriptor)) {
                interest.drop(i)
                byToken.remove(token)
            } else i++
        }
        settle(interest, sink)
    }
}
