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
 * Fill: [receives] RECVMSG windows stay armed. Drain, once per wake: one nonblocking reap takes every completion, each
 * datagram goes to [table] in place (the window's payload starts at offset 0), [SctpTcbTable.flush] runs once, and its
 * packets leave as SENDMSGs in the same submit as the re-armed receives. A wake is reactor readiness on the socket,
 * the next T1/T3 deadline, or [kick] from the layer above after it queued a message.
 *
 * Every association of the port shares the one socket: the kernel holds one descriptor however many connections run.
 */
class SctpUdpPort(
    val channels: ChannelOperations,
    val reactor: ReactorOperations,
    val local: Endpoint,
    upper: SctpUpper,
    secret: ByteArray,
    val receives: Int = 64,
    val sends: Int = 1024,
    mtu: Int = 1452,
) : SctpSink {
    val table = SctpTcbTable(local.b, upper, this, secret, mtu)
    val window = mtu + UringOp.DATAGRAM_ADDRESS
    val handle: ChannelOperations.ChannelHandle = channels.openChannel(receives + sends)
    val receiveWindows = Array(receives) { ByteArray(window) }
    val sendWindows = Array(sends) { ByteArray(window) }
    /** Receive completions reaped while the table was mid-call: (userData, result) pairs for the next wake. */
    var deferred = LongArray(2 * receives)
    var deferredCount = 0
    /** Free send windows, a stack of indexes into [send]. */
    val free = IntArray(sends) { sends - 1 - it }
    var freeCount = sends
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

    /** UDP socket bound to [local], every receive window armed. */
    fun open(): SctpUdpPort {
        val domain = if (local.a.size == 4) SocketDomain.AF_INET else SocketDomain.AF_INET6
        fd = settle(handle.prepSocket(domain.posix, SocketType.SOCK_DGRAM.mask or NONBLOCK, SocketProtocol.IPPROTO_UDP.posix))
        check(fd >= 0) { "UDP socket: $fd" }
        val bound = settle(handle.prepBind(fd, ByteBuffer(local.sockaddr)))
        check(bound == 0) { "bind ${local.a.joinToString(".") { (it.toInt() and 255).toString() }}:${local.b}: $bound" }
        for (i in 0 until receives) check(handle.recvmsg(fd, ByteBuffer(receiveWindows[i], 0, window), i.toLong()) == 0)
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
        val i = free[--freeCount]
        val w = sendWindows[i]
        packet.copyInto(w, 0, 0, length)
        paths[path].copyInto(w, length)
        check(handle.sendmsg(fd, ByteBuffer(w, 0, length + UringOp.DATAGRAM_ADDRESS), SEND_BASE + i) == 0) { "send window $i refused" }
    }

    /**
     * Send windows ran out inside a table call: submit, wait for one completion or more; sends free their windows and
     * receives wait in [deferred], because the table is mid-call and its packet writer is in use.
     */
    fun reclaim() {
        handle.submit()
        for (r in handle.wait(1)) {
            if (r.userData >= SEND_BASE) free[freeCount++] = (r.userData - SEND_BASE).toInt()
            else {
                if (deferredCount == deferred.size) deferred = deferred.copyOf(deferredCount * 2)
                deferred[deferredCount++] = r.userData
                deferred[deferredCount++] = r.res.toLong()
            }
        }
    }

    fun complete(userData: Long, res: Int, now: Long) {
        if (userData >= SEND_BASE) {
            free[freeCount++] = (userData - SEND_BASE).toInt()
            return
        }
        val i = userData.toInt()
        val w = receiveWindows[i]
        if (res > 0) table.receive(w, res, pathOf(w, window - UringOp.DATAGRAM_ADDRESS), now)
        check(handle.recvmsg(fd, ByteBuffer(w, 0, window), userData) == 0) { "receive window $i not re-armed" }
    }

    /** One wake's drain: every completion, one flush, one submit. */
    fun pump() {
        val now = now()
        var k = 0
        while (k < deferredCount) { complete(deferred[k], deferred[k + 1].toInt(), now); k += 2 }
        deferredCount = 0
        for (r in handle.wait(0)) complete(r.userData, r.res, now)
        table.flush(now)
        handle.submit()
    }

    /** Packets the layer above queued outside a wake (a COMMIT's parked responses): one flush, one submit. */
    fun flush() {
        table.flush(now())
        handle.submit()
    }

    /** Until the socket is readable, [deadline] (owner's clock; Long.MAX_VALUE: none) passes, or [kick] fires. */
    suspend fun waitWake(deadline: Long) {
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
     * After the owner loop returned: the armed receives are cancelled and every outstanding completion reaped (a
     * ring closes only once its requests have terminal CQEs), then the ring, then the socket.
     */
    fun close() {
        reactor.deregister(fd)
        handle.submit()
        // Receives still armed: not those parked in [deferred]; a receive completing below is not re-armed.
        var armed = receives - deferredCount / 2
        while (freeCount < sends) for (r in handle.wait(1)) if (r.userData >= SEND_BASE) freeCount++ else armed--
        var outstanding = armed
        for (i in 0 until receives) if (handle.prepCancel(i.toLong(), CANCEL_BASE + i) == 0) outstanding++
        handle.submit()
        while (outstanding > 0) outstanding -= handle.wait(1).size
        handle.close()
        check(channels.close(fd) == 0) { "close UDP socket $fd" }
    }

    companion object {
        /** Linux SOCK_NONBLOCK in the socket type, as the ring's SOCKET takes it. */
        const val NONBLOCK = 0x800
        /** userData of send windows: SEND_BASE + index; receive windows use their index. */
        const val SEND_BASE = 1L shl 32
        /** userData of the cancels [close] submits: CANCEL_BASE + receive index. */
        const val CANCEL_BASE = 2L shl 32
    }
}
