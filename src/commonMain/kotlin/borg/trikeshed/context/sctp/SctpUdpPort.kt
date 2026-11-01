package borg.trikeshed.sctp

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.Endpoint
import borg.trikeshed.userspace.nio.channels.SocketDomain
import borg.trikeshed.userspace.nio.channels.SocketProtocol
import borg.trikeshed.userspace.nio.channels.SocketType
import borg.trikeshed.userspace.nio.channels.sockaddr
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ReactorOperations
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * SCTP over UDP (RFC 6951) on one socket: a [SctpTcbTable] driven fill/drain through the userspace ring.
 *
 * The wheel: [wheel] datagram windows, made once, recycled and never cleared. Window b below [half] receives; its
 * counterpart b xor [half] sends. The window index is the request's userData, so one bit tells the halves apart.
 * Receives turn through every window of their half, so that half stays in cache only if all of it fits; sends take
 * the window freed last, so only the sends in flight are hot. The default, 2 × 32 windows of 1,480 bytes (92.5 KB),
 * fits in half of a 256 KB L2: the smallest L2 per core of an x86 server, which a droplet's vCPU shares with the
 * core's other hyperthread.
 *
 * Fill: the receive half stays armed. Drain, once per wake: one nonblocking reap takes every completion. A send
 * completion frees its window at once; each datagram goes to [table] in place (the window's payload starts at offset
 * 0). [SctpTcbTable.flush] runs once, and its packets leave as SENDMSGs in the same submit as the re-armed receives.
 * A wake is reactor readiness on the socket, the next T1/T3 deadline, or [kick] from the layer above after it queued
 * a message.
 *
 * Every association of the port shares the one socket: the kernel holds one descriptor however many connections run.
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
) : SctpSink {
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
     * Receive completions the table has not had yet: (window, result) pairs in a ring of [half] slots, the counters
     * taken modulo [half]. A receive window re-arms only after the table had its datagram, so no more than [half] wait.
     */
    val received = IntArray(2 * half)
    var receivedHead = 0
    var receivedTail = 0
    /** Free send windows: a stack, so the window freed last, still in cache, goes out next. */
    val free = IntArray(half) { wheel - 1 - it }
    var freeCount = half
    /** Peer addresses: the path index is the position; [lastPath] short-cuts a run of datagrams from one peer. */
    val paths = ArrayList<ByteArray>()
    var lastPath = -1
    val kick = Channel<Unit>(Channel.CONFLATED)
    val origin = TimeSource.Monotonic.markNow()
    var fd = -1

    fun now(): Long = origin.elapsedNow().inWholeNanoseconds

    /** One socket SQE, submitted and settled. */
    fun settle(prepared: Int): Int {
        check(prepared >= 0) { "socket SQE refused: $prepared" }
        check(handle.submit() == 1) { "socket SQE was not submitted" }
        return handle.wait(1).single().res
    }

    /** UDP socket bound to [local], the receive half armed. */
    fun open(): SctpUdpPort {
        val domain = if (local.a.size == 4) SocketDomain.AF_INET else SocketDomain.AF_INET6
        fd = settle(handle.prepSocket(domain.posix, SocketType.SOCK_DGRAM.mask or NONBLOCK, SocketProtocol.IPPROTO_UDP.posix))
        check(fd >= 0) { "UDP socket: $fd" }
        val bound = settle(handle.prepBind(fd, ByteBuffer(local.sockaddr)))
        check(bound == 0) { "bind ${local.a.joinToString(".") { (it.toInt() and 255).toString() }}:${local.b}: $bound" }
        for (b in 0 until half) check(handle.recvmsg(fd, buffers[b], b.toLong()) == 0)
        handle.submit()
        reactor.register(fd, setOf(Interest.READ))
        return this
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
        while (freeCount == 0) reclaim()
        val b = free[--freeCount]
        val w = windows[b]
        packet.copyInto(w, 0, 0, length)
        paths[path].copyInto(w, length)
        check(handle.sendmsg(fd, buffers[b].limit(length + UringOp.DATAGRAM_ADDRESS), b.toLong()) == 0) { "send window $b refused" }
    }

    /** The send half ran out inside a table call: submit, then reap one completion or more. */
    fun reclaim() {
        handle.submit()
        reap(1)
    }

    /**
     * Completions into their halves: a send frees its window at once; a receive waits in [received], because the
     * table may be mid-call with its packet writer in use.
     */
    fun reap(minComplete: Int) {
        for (r in handle.wait(minComplete)) {
            val b = r.userData.toInt()
            if (b and half != 0) free[freeCount++] = b
            else {
                val s = (receivedTail++ and (half - 1)) shl 1
                received[s] = b
                received[s + 1] = r.res
            }
        }
    }

    /** Receive window [b]'s datagram ([res] bytes) into the table, then the window re-armed as it is. */
    fun receive(b: Int, res: Int, now: Long) {
        val w = windows[b]
        if (res > 0) table.receive(w, res, pathOf(w, window - UringOp.DATAGRAM_ADDRESS), now)
        check(handle.recvmsg(fd, buffers[b], b.toLong()) == 0) { "receive window $b not re-armed" }
    }

    /**
     * Every waiting datagram into the table, then a flush. A send that runs out of windows reaps more receives; they
     * go to the table before this returns, because their datagrams no longer make the socket readable.
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

    /** One wake: every completion, the datagrams into the table, one flush, one submit. */
    fun pump() {
        val now = now()
        reap(0)
        drain(now)
        submitAndReap()
    }

    /** Packets the layer above queued outside a wake (a COMMIT's parked responses): one flush, one submit. */
    fun flush() {
        drain(now())
        submitAndReap()
    }

    /**
     * The submit of the SENDMSGs and re-armed receives, then a reap. A receive the submit completes at once took its
     * datagram off the socket, so the socket no longer wakes the owner for it: [kick] wakes the next turn instead.
     */
    fun submitAndReap() {
        handle.submit()
        reap(0)
        if (receivedHead != receivedTail) kick.trySend(Unit)
    }

    /** Until the socket is readable, [deadline] (owner's clock; Long.MAX_VALUE: none) passes, or [kick] fires. */
    suspend fun waitWake(deadline: Long) {
        // A kick already sent needs no reactor wait.
        if (kick.tryReceive().isSuccess) return
        val timeout = if (deadline == Long.MAX_VALUE) Duration.INFINITE else maxOf(0L, deadline - now()).nanoseconds
        coroutineScope {
            val observation = async(start = CoroutineStart.UNDISPATCHED) { reactor.poll(timeout) }
            select {
                kick.onReceiveCatching { }
                observation.onAwait { }
            }
            observation.cancelAndJoin()
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
     * After the owner loop returned: the sends in flight reaped, the armed receives cancelled and reaped (a ring
     * closes only once its requests have terminal CQEs), then the ring, then the socket.
     */
    fun close() {
        reactor.deregister(fd)
        handle.submit()
        // Receives still armed: not those waiting in [received]; a receive completing below is not re-armed.
        var armed = half - (receivedTail - receivedHead)
        while (freeCount < half) for (r in handle.wait(1)) if (r.userData.toInt() and half != 0) freeCount++ else armed--
        var outstanding = armed
        // A cancel's userData is [wheel] + its target: outside every window's index.
        for (b in 0 until half) if (handle.prepCancel(b.toLong(), (wheel + b).toLong()) == 0) outstanding++
        handle.submit()
        while (outstanding > 0) outstanding -= handle.wait(1).size
        handle.close()
        check(channels.close(fd) == 0) { "close UDP socket $fd" }
    }

    companion object {
        /** Linux SOCK_NONBLOCK in the socket type, as the ring's SOCKET takes it. */
        const val NONBLOCK = 0x800
    }
}
