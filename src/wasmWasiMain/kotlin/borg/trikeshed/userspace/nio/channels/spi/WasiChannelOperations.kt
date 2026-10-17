package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.EmulatedRingRecycler
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.WasiUserspaceChannelBackend
import borg.trikeshed.userspace.emulatedRingDepth
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.platform.spi.platformCacheTopology
import borg.trikeshed.userspace.ringResidencyBytes

/**
 * WASI channels: emulated rings, recycled. A kernel ring is an exclusive channel; an emulated one is serviced here, so
 * [openChannel] sizes it by the cache that holds a wide op's blocks ([emulatedRingDepth] over one page per op) and hands
 * back a released ring of that depth before it builds another. WASI preview 1 creates no sockets: SOCKET, BIND, LISTEN,
 * ACCEPT and CONNECT complete with -EOPNOTSUPP like any unsupported SQE.
 */
class WasiChannelOperations : ChannelOperations {
    private val rings = EmulatedRingRecycler<WasiChannelHandle>({ it.depth }) { WasiChannelHandle(it, this) }

    /** Rings held for reuse. */
    val held: Int get() = rings.held

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle =
        rings.acquire(emulatedRingDepth(entries, PAGE, residency)).also { it.open() }

    internal fun release(ring: WasiChannelHandle) = rings.release(ring)

    private companion object {
        const val PAGE = 4096
        /** WASI reports no cache; 32 KiB is the smallest L1d of current x86-64 and arm64 cores (estimate). */
        const val RESIDENCY_ESTIMATE = 32L * 1024
        val residency: Long = platformCacheTopology.ringResidencyBytes.takeIf { it > 0 } ?: RESIDENCY_ESTIMATE
    }
}

/** One emulated ring of [depth] SQEs; [close] settles it and returns it to its [operations] for reuse. */
class WasiChannelHandle internal constructor(internal val depth: Int, private val operations: WasiChannelOperations) :
    ChannelOperations.ChannelHandle {
    override val id: Int get() = depth
    private val backend = WasiUserspaceChannelBackend(depth)
    private val staged = ArrayList<UringSubmission>(depth)
    private val callers = ArrayList<Pair<Int, Long>>(depth)
    private val settled = ArrayList<ChannelResult>(depth)
    private var open = false

    internal fun open() { open = true }

    private fun enqueue(sub: UringSubmission, userData: Long): Int {
        if (!open) return -9
        if (staged.size + settled.size == depth) return -11
        staged += sub.copy(userData = callers.size.toLong())
        callers += sub.fd to userData
        return 0
    }

    // This handle is a ring, not a descriptor.
    override fun read(buffer: ByteBuffer, offset: Long): Int = -9
    override fun write(buffer: ByteBuffer, offset: Long): Int = -9

    override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.READ, fd, 0, buffer.remaining(), -1, buffer = buffer), userData)
    override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.WRITE, fd, 0, buffer.remaining(), -1, buffer = buffer), userData)
    override fun prepAccept(serverFd: Int, userData: Long): Int = enqueue(UringSubmission(UringOp.ACCEPT, serverFd, 0, 0, 0), userData)
    override fun prepSocket(domain: Int, type: Int, protocol: Int, userData: Long): Int =
        enqueue(UringSubmission(UringOp.SOCKET, domain, 0, protocol, type.toLong()), userData)
    override fun prepBind(fd: Int, address: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.BIND, fd, 0, address.remaining(), 0, buffer = address), userData)
    override fun prepListen(fd: Int, backlog: Int, userData: Long): Int = enqueue(UringSubmission(UringOp.LISTEN, fd, 0, backlog, 0), userData)
    override fun prepConnect(fd: Int, address: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.CONNECT, fd, 0, address.remaining(), 0, buffer = address), userData)
    override fun prepClose(fd: Int, userData: Long): Int = enqueue(UringSubmission(UringOp.CLOSE, fd, 0, 0, 0), userData)

    override fun submit(): Int {
        if (!open) return -9
        val count = staged.size
        if (count == 0) return 0
        for (r: SelectionResult in backend.submitBatch(staged)) {
            val (fd, caller) = callers[r.userData.toInt()]
            settled += ChannelResult(fd, r.res, caller)
        }
        staged.clear()
        return count
    }

    override fun wait(minComplete: Int): List<ChannelResult> {
        if (settled.size < minComplete) submit()
        val out = settled.toList()
        settled.clear()
        if (staged.isEmpty()) callers.clear()
        return out
    }

    /** Settles what is staged, drops every reference to the caller's buffers and returns the ring for reuse. */
    override fun close() {
        if (!open) return
        submit()
        staged.clear(); callers.clear(); settled.clear()
        open = false
        operations.release(this)
    }
}
