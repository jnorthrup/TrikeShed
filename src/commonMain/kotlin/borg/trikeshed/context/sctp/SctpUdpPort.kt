package borg.trikeshed.sctp

import borg.trikeshed.lib.j
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ByteOrder
import borg.trikeshed.userspace.nio.channels.Endpoint
import borg.trikeshed.userspace.nio.channels.Port
import borg.trikeshed.userspace.nio.channels.SocketDomain
import borg.trikeshed.userspace.nio.channels.SocketProtocol
import borg.trikeshed.userspace.nio.channels.SocketType
import borg.trikeshed.userspace.nio.channels.UnixName
import borg.trikeshed.userspace.nio.channels.sockaddr
import borg.trikeshed.userspace.nio.channels.sockaddrUnixName
import borg.trikeshed.userspace.nio.channels.unixSlot
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ParkingReactor
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * SCTP over UDP (RFC 6951) on one socket: a [SctpTcbTable] driven fill/drain through the userspace ring. With [unix] it
 * runs over AF_UNIX datagrams instead, for hops between processes of one host.
 *
 * The wheel: [wheel] datagram windows, made once, recycled and never cleared. Window b below [half] receives; its
 * counterpart b xor [half] sends. The window index is the request's userData, so one bit tells the halves apart.
 * Both halves keep their free windows on a stack, so the window freed last, still in cache, goes out next. The
 * default, 2 × 32 windows of 1,480 bytes (92.5 KB), fits in half of a 256 KB L2: the smallest L2 per core of an x86
 * server, which a droplet's vCPU shares with the core's other hyperthread.
 *
 * Fill: free receive windows go to the ring as RECVMSGs with [UringOp.MSG_DONTWAIT], in the same submit as the
 * wake's SENDMSGs. Each completes inside that submit, with a datagram or -EAGAIN, so nothing stays armed: a kernel
 * ring holds no poll for them, so several receives on the one socket never all wake for each datagram, and no
 * datagram is taken outside a wake. The reactor's readiness on the socket stays true to the queue. A fill that met
 * an empty socket leaves the port [drained]: until the reactor next reports the socket readable, wakes fill nothing.
 * The fill size follows the traffic: it doubles after a fill that every window took a datagram for, up to [half],
 * and is twice the datagrams found after one that emptied the socket.
 *
 * Drain, once per wake: one nonblocking reap takes every completion. A send completion frees its window at once;
 * each datagram goes to [table] in place (the window's payload starts at offset 0), then its window is free again.
 * [SctpTcbTable.flush] runs once. A wake is reactor readiness on the socket, the next T1/T3 deadline, or [kick] from
 * the layer above after it queued a message. A [ParkingReactor] makes the wait one poll(2) on the owner's own thread;
 * any other reactor is awaited through a coroutine of its own.
 *
 * Every association of the port shares the one socket: the kernel holds one descriptor however many connections run.
 *
 * Over AF_UNIX the packets are the same and the window's address slot holds a unix name (see UringOp.DATAGRAM_ADDRESS).
 * A unix receiver queues net.unix.max_dgram_qlen datagrams, then refuses more: where UDP drops silently, a unix send
 * completes -EAGAIN at once (it is MSG_DONTWAIT), the port keeps its window, and [resend] sends it at a later wake, one
 * millisecond on at the latest. No packet is lost to a full queue unless every send window is refused at once.
 */
class SctpUdpPort(
    val channels: ChannelOperations,
    val reactor: ReactorOperations,
    val local: Endpoint,
    upper: SctpUpper,
    secret: ByteArray,
    /** Windows in each half of the wheel, a power of two. */
    val half: Int = 32,
    mtu: Int = 1452,
    /**
     * Bytes asked for the socket's receive and send buffers. Every association shares the one socket, so its receive
     * buffer holds all of their arrivals while the owner is busy: arrival rate times the longest stall (a COMMIT, a
     * collection). 4 MB holds about 100 ms at 128k users; a host caps or refuses more (see [sizeBuffer]).
     */
    val socketBuffer: Int = 4 shl 20,
    /**
     * Not null: AF_UNIX datagrams bound to this name in place of UDP on [local]'s address; only [local]'s port is used,
     * as the SCTP port of the packets. An empty name lets the kernel pick one (Linux's autobind; other hosts bind none
     * and cannot be answered). A file name is the caller's to unlink.
     */
    val unix: UnixName? = null,
) : SctpSink {
    /** An AF_UNIX port: datagrams on [name], [sctpPort] in the packets' port fields. */
    constructor(
        channels: ChannelOperations, reactor: ReactorOperations, name: UnixName, sctpPort: Port, upper: SctpUpper,
        secret: ByteArray, half: Int = 32, mtu: Int = 1452, socketBuffer: Int = 4 shl 20,
    ) : this(channels, reactor, ByteArray(0) j sctpPort, upper, secret, half, mtu, socketBuffer, name)

    init { require(half > 0 && half and (half - 1) == 0) { "half is not a power of two: $half" } }

    val table = SctpTcbTable(local.b, upper, this, secret, mtu)
    val window = mtu + UringOp.DATAGRAM_ADDRESS
    /** Both halves: receive windows 0 until [half], send windows [half] until [wheel]. */
    val wheel = 2 * half
    val handle: ChannelOperations.ChannelHandle = channels.openChannel(wheel)
    val windows = Array(wheel) { ByteArray(window) }
    /**
     * One buffer per window for its whole life: datagram operations never move the position, so a receive re-arms
     * as it is and a send sets only its limit.
     */
    val buffers = Array(wheel) { ByteBuffer(windows[it], 0, window) }
    /**
     * Datagrams the table has not had yet: (window, length) pairs in a ring of [half] slots, the counters taken modulo
     * [half]. Only receive windows wait here, so no more than [half] do.
     */
    val received = IntArray(2 * half)
    var receivedHead = 0
    var receivedTail = 0
    /** Free receive windows: a stack. */
    val idle = IntArray(half) { half - 1 - it }
    var idleCount = half
    /** Receives in the ring, and what the current fill has come back with. */
    var armed = 0
    var filled = 0
    var emptied = 0
    /** Receives the next fill arms; see the class comment. */
    var fillSize = FILL_MIN
    /** The last fill met an empty socket: only readiness starts the next fill. */
    var drained = true
    /** The current wake came from the reactor's readiness on the socket. */
    var readable = false
    /** Free send windows: a stack, so the window freed last, still in cache, goes out next. */
    val free = IntArray(half) { wheel - 1 - it }
    var freeCount = half
    /** Send windows a full peer queue refused (-EAGAIN, -ENOBUFS), waiting for [resend]: a stack. Neither free nor in the ring. */
    val blocked = IntArray(half)
    var blockedCount = 0
    /** Sends refused so far. */
    var refused = 0L
    /** Packets [send] gave up on: every send window was refused by a full peer queue, and refused again at once. */
    var lost = 0L
    /** The msg flags of a send: a unix peer's full queue must refuse at once; a UDP send waits in the ring for send space. */
    val sendFlags = if (unix != null) UringOp.MSG_DONTWAIT else 0
    /** Peer addresses: the path index is the position; [lastPath] short-cuts a run of datagrams from one peer. */
    val paths = ArrayList<ByteArray>()
    var lastPath = -1
    val kick = Channel<Unit>(Channel.CONFLATED)
    val origin = TimeSource.Monotonic.markNow()
    var fd = -1
    /** The reactor's own blocking wait, when it has one. */
    val parking: ParkingReactor? = reactor as? ParkingReactor
    /** [reap]'s completions: the window and the result of each, at most one per window. */
    val doneWindow = LongArray(2 * half)
    val doneRes = IntArray(2 * half)

    fun now(): Long = origin.elapsedNow().inWholeNanoseconds

    /** One socket SQE, submitted and settled. */
    fun settle(prepared: Int): Int {
        check(prepared >= 0) { "socket SQE refused: $prepared" }
        check(handle.submit() == 1) { "socket SQE was not submitted" }
        return handle.wait(1).single().res
    }

    /** Datagram socket bound to [local], or to [unix]'s name, watched for readiness; the first readable wake fills the receive half. */
    fun open(): SctpUdpPort {
        val name = unix
        val domain = if (name != null) SocketDomain.AF_UNIX else if (local.a.size == 4) SocketDomain.AF_INET else SocketDomain.AF_INET6
        val protocol = if (name != null) SocketProtocol.IPPROTO_IP else SocketProtocol.IPPROTO_UDP
        fd = settle(handle.prepSocket(domain.posix, SocketType.SOCK_DGRAM.mask or NONBLOCK, protocol.posix))
        check(fd >= 0) { "${if (name != null) "unix datagram" else "UDP"} socket: $fd" }
        val bound = settle(handle.prepBind(fd, ByteBuffer(if (name != null) sockaddrUnixName(name) else local.sockaddr)))
        check(bound == 0) {
            if (name != null) "bind unix ${name.decodeToString().replace('\u0000', '@')}: $bound"
            else "bind ${local.a.joinToString(".") { (it.toInt() and 255).toString() }}:${local.b}: $bound"
        }
        sizeBuffer(UringOp.SO_RCVBUF)
        sizeBuffer(UringOp.SO_SNDBUF)
        reactor.register(fd, setOf(Interest.READ))
        return this
    }

    /**
     * [socketBuffer] bytes for one of the socket's buffers, halved until the host takes it: Linux caps a request at
     * rmem_max/wmem_max (and doubles it for its bookkeeping); macOS refuses one above kern.ipc.maxsockbuf. Below 64 KB
     * the host's default stays.
     */
    fun sizeBuffer(option: Int) {
        var bytes = socketBuffer
        while (bytes >= 64 shl 10) {
            val value = ByteBuffer(4).order(ByteOrder.nativeOrder()).putInt(0, bytes).array()
            if (settle(handle.prepSetsockopt(fd, UringOp.SOL_SOCKET, option, value)) == 0) return
            bytes = bytes shr 1
        }
    }

    /** The path index of a peer's Linux sockaddr (as sockaddr.kt frames it): [Endpoint.sockaddr] for a dial. */
    fun pathOf(address: ByteArray, at: Int = 0): SctpPath {
        val size = if (address[at].toInt() == 2) 16 else 28
        val last = lastPath
        if (last >= 0 && same(paths[last], address, at, size)) return last
        for (i in paths.indices) if (same(paths[i], address, at, size)) { lastPath = i; return i }
        val copy = ByteArray(UringOp.DATAGRAM_ADDRESS)
        address.copyInto(copy, 0, at, at + size)
        paths += copy
        lastPath = paths.size - 1
        return lastPath
    }

    fun same(p: ByteArray, address: ByteArray, at: Int, size: Int): Boolean {
        for (i in 0 until size) if (p[i] != address[at + i]) return false
        return true
    }

    /** An association toward the AF_UNIX socket [name], whose SCTP port is [port]; [SctpUpper.associated] follows its COOKIE ACK. */
    fun connect(name: UnixName, port: Port): SctpTcb {
        val t = table.connect(pathOf(unixSlot(name)), port, now())
        kick.trySend(Unit)
        return t
    }

    /** An association toward [peer]; [SctpUpper.associated] follows its COOKIE ACK. */
    fun connect(peer: Endpoint): SctpTcb {
        val t = table.connect(pathOf(peer.sockaddr), peer.b, now())
        kick.trySend(Unit)
        return t
    }

    /** Queues a message on [t] and wakes the owner loop, from any coroutine of the owner's thread. */
    fun submit(t: SctpTcb, ppid: Ppid, bytes: ByteArray, offset: Int, length: Int) {
        table.send(t, ppid, bytes, offset, length)
        kick.trySend(Unit)
    }

    /** [SctpSink]: the packet and its path's sockaddr into a free send window, as one SENDMSG. */
    override fun send(path: SctpPath, packet: ByteArray, length: Int) {
        while (freeCount == 0) if (!reclaim()) { lost++; return }
        val b = free[--freeCount]
        val w = windows[b]
        packet.copyInto(w, 0, 0, length)
        paths[path].copyInto(w, length)
        check(handle.sendmsg(fd, buffers[b].limit(length + UringOp.DATAGRAM_ADDRESS), b.toLong(), sendFlags) == 0) { "send window $b refused" }
    }

    /**
     * The send half ran out inside a table call: refused sends go out again, the ring is submitted, and one completion
     * or more reaped. False when no window came back, every window being refused by a full peer queue: the packet is
     * lost then, as a UDP datagram dropped at its receiver is, and the association's timers send it again.
     */
    fun reclaim(): Boolean {
        resend()
        handle.submit()
        reap(1)
        // A wait may return receives only: while a send is still in the ring, its window comes back at a later wait.
        while (freeCount == 0 && blockedCount < half) reap(1)
        return freeCount > 0
    }

    /** The windows a full peer queue refused, as SENDMSGs again; each still holds its packet and its address. */
    fun resend() {
        var n = blockedCount
        blockedCount = 0
        while (n > 0) {
            val b = blocked[--n]
            check(handle.sendmsg(fd, buffers[b], b.toLong(), sendFlags) == 0) { "send window $b refused" }
        }
    }

    /**
     * Completions into their halves: a send frees its window at once, unless a full peer queue refused it, which parks
     * the window in [blocked]; a datagram waits in [received], because the table may be mid-call with its packet writer
     * in use; a receive that found no datagram frees its window.
     */
    fun reap(minComplete: Int) {
        val n = handle.waitInto(minComplete, doneWindow, doneRes)
        for (i in 0 until n) {
            val b = doneWindow[i].toInt()
            if (b and half != 0) {
                // A unix peer's full queue refuses at once: the window waits for [resend]. A UDP send the kernel
                // refuses is lost, as before, and its window is free.
                val full = doneRes[i] == -EAGAIN || doneRes[i] == -ENOBUFS
                if (full && unix != null) { blocked[blockedCount++] = b; refused++ } else free[freeCount++] = b
            } else {
                armed--
                val res = doneRes[i]
                if (res >= 0) {
                    filled++
                    val s = (receivedTail++ and (half - 1)) shl 1
                    received[s] = b
                    received[s + 1] = res
                } else {
                    emptied++
                    idle[idleCount++] = b
                }
            }
        }
    }

    /** Receive window [b]'s datagram ([res] bytes) into the table; the window is free again. */
    fun receive(b: Int, res: Int, now: Long) {
        val w = windows[b]
        // A unix sender that never bound leaves its slot zero, family included: there is nowhere to answer it.
        if (res > 0 && w[window - UringOp.DATAGRAM_ADDRESS].toInt() != 0) table.receive(w, res, pathOf(w, window - UringOp.DATAGRAM_ADDRESS), now)
        idle[idleCount++] = b
    }

    /**
     * Every waiting datagram into the table, then a flush. A send that runs out of windows reaps more datagrams; they
     * go to the table before this returns.
     */
    fun drain(now: Long) {
        do {
            while (receivedHead != receivedTail) {
                val s = (receivedHead++ and (half - 1)) shl 1
                receive(received[s], received[s + 1], now)
            }
            table.flush(now)
        } while (receivedHead != receivedTail)
    }

    /** Up to [fillSize] free receive windows as MSG_DONTWAIT RECVMSGs, for the next submit. */
    fun fill() {
        var n = minOf(fillSize, idleCount)
        filled = 0
        emptied = 0
        while (n-- > 0) {
            val b = idle[--idleCount]
            check(handle.recvmsg(fd, buffers[b], b.toLong(), UringOp.MSG_DONTWAIT) == 0) { "receive window $b refused" }
            armed++
        }
    }

    /** One wake: every completion, the datagrams into the table, one flush, then the fill and the sends in one submit. */
    fun pump() {
        val now = now()
        reap(0)
        drain(now)
        if (readable || !drained) fill()
        readable = false
        resend()
        submitAndReap()
    }

    /** Packets the layer above queued outside a wake (a COMMIT's parked responses): one flush, one submit. */
    fun flush() {
        drain(now())
        resend()
        submitAndReap()
    }

    /**
     * The submit of the SENDMSGs and the fill, then a reap: the fill's receives complete inside the submit. Their
     * datagrams wait for the next turn, which [kick] starts; a fill that met an empty socket leaves the port [drained].
     */
    fun submitAndReap() {
        handle.submit()
        reap(0)
        if (filled + emptied > 0 && armed == 0) {
            drained = emptied > 0
            fillSize = if (drained) minOf(half, maxOf(FILL_MIN, 2 * filled)) else minOf(half, 2 * fillSize)
            filled = 0
            emptied = 0
        }
        if (receivedHead != receivedTail) kick.trySend(Unit)
    }

    /**
     * Until the socket is readable, [deadline] (owner's clock; Long.MAX_VALUE: none) passes, or [kick] fires. A refused
     * send is not a reason to wait on the socket: with any waiting, the wake is [RETRY] nanoseconds on at the latest.
     */
    suspend fun waitWake(deadline: Long) {
        // A kick already sent needs no reactor wait.
        if (kick.tryReceive().isSuccess) return
        val due = if (blockedCount > 0) minOf(deadline, now() + RETRY) else deadline
        val parked = parking
        if (parked != null) {
            readable = parked.park(if (due == Long.MAX_VALUE) -1L else maxOf(0L, due - now()))
            return
        }
        val timeout = if (due == Long.MAX_VALUE) Duration.INFINITE else maxOf(0L, due - now()).nanoseconds
        coroutineScope {
            val observation = async(start = CoroutineStart.UNDISPATCHED) { reactor.poll(timeout) }
            readable = select {
                kick.onReceiveCatching { false }
                observation.onAwait { it.isNotEmpty() }
            }
            // cancelAndJoin() would make a CancellationException per wake, and a Kotlin/Native Throwable walks the stack.
            observation.cancel(WOKEN)
            observation.join()
        }
    }

    /** The owner loop of a port with nothing else to wait for; it returns when [stop] closes [kick]. */
    suspend fun run() {
        while (!kick.isClosedForReceive) {
            waitWake(table.nextDeadline())
            pump()
        }
    }

    fun stop() { kick.close() }

    /**
     * After the owner loop returned: the sends in flight and any fill's receives reaped (a ring closes only once its
     * requests have terminal CQEs; MSG_DONTWAIT receives end inside their submit; refused sends are not in the ring),
     * then the ring, then the socket.
     */
    fun close() {
        reactor.deregister(fd)
        handle.submit()
        while (freeCount + blockedCount < half || armed > 0) reap(1)
        handle.close()
        check(channels.close(fd) == 0) { "close datagram socket $fd" }
    }

    companion object {
        /** Linux SOCK_NONBLOCK in the socket type, as the ring's SOCKET takes it. */
        const val NONBLOCK = 0x800
        /** The smallest fill. */
        const val FILL_MIN = 8
        /** Linux errno values of a send a full peer queue refuses: EAGAIN, and ENOBUFS as macOS answers. */
        const val EAGAIN = 11
        const val ENOBUFS = 105
        /** The longest a refused send waits for its next try, in nanoseconds of the owner's clock. */
        const val RETRY = 1_000_000L
        /** What a wake cancels the reactor's wait with: made once, because making one captures a stack trace. */
        val WOKEN = CancellationException("woken")
    }
}
