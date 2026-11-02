@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.cinterop.*
import zlinux_uring.io_uring
import zlinux_uring.io_uring_cq_ready
import zlinux_uring.io_uring_queue_exit
import zlinux_uring.io_uring_queue_init
import zlinux_uring.k_wheel_enter
import zlinux_uring.k_wheel_prep
import zlinux_uring.k_wheel_reap

/**
 * The windows of a datagram port on a kernel ring, as one wheel. The window index is the SQE's user_data and so the
 * CQE's: no request table, no token, no per-request record. A window is busy from its SQE to its CQE, and the port
 * never has two requests on one window. A window's msghdr and iovec are made once, in native memory; its array is
 * pinned once, so a request writes only the fields that change (the payload length, the name length and, for a send,
 * where the name starts) and one SQE.
 *
 * Layout of a window, as the facade's SENDMSG and RECVMSG define it: the payload from the window's start, then the
 * peer's Linux sockaddr in the last [UringOp.DATAGRAM_ADDRESS] bytes of the buffer's remaining span. A send names its
 * family's length (16 for AF_INET); a receive offers all 28 bytes.
 *
 * One owner: nothing here is locked.
 */
internal class DatagramWheel private constructor(val windows: Int, private val ring: CPointer<io_uring>) {
    private val msgs = nativeHeap.allocArray<platform.posix.msghdr>(windows)
    private val iovs = nativeHeap.allocArray<platform.posix.iovec>(windows)
    private val doneWindow = nativeHeap.allocArray<ULongVar>(windows)
    private val doneRes = nativeHeap.allocArray<IntVar>(windows)
    private val pins = arrayOfNulls<Pinned<ByteArray>>(windows)
    private val bound = arrayOfNulls<ByteArray>(windows)
    private val busy = BooleanArray(windows)
    private val fds = IntArray(windows)
    /** SQEs prepared and not yet reaped. */
    var pending = 0
        private set
    /** SQEs prepared and not yet submitted. */
    var queued = 0
        private set

    init {
        platform.posix.memset(msgs, 0, (windows * sizeOf<platform.posix.msghdr>()).convert())
        platform.posix.memset(iovs, 0, (windows * sizeOf<platform.posix.iovec>()).convert())
        for (w in 0 until windows) {
            val m = msgs[w]
            m.msg_iov = iovs[w].ptr
            m.msg_iovlen = 1u
        }
    }

    /**
     * One datagram request over [buffer]'s window, completing as window [slot] (the caller's userData). 0, or -errno:
     * -22 for a slot outside the wheel or a window too short for its address, -16 when [slot] is still busy.
     */
    fun prep(send: Boolean, fd: Int, buffer: ByteBuffer, slot: Int, flags: Int): Int {
        if (slot < 0 || slot >= windows) return -22
        if (busy[slot]) return -16
        val array = buffer.array()
        val start = buffer.arrayOffset() + buffer.position()
        val payload = buffer.remaining() - UringOp.DATAGRAM_ADDRESS
        if (payload < 0) return -22
        if (bound[slot] !== array) {
            pins[slot]?.unpin()
            pins[slot] = array.pin()
            bound[slot] = array
        }
        val base = pins[slot]!!.addressOf(start)
        val iov = iovs[slot]
        iov.iov_base = base
        iov.iov_len = payload.toULong()
        val m = msgs[slot]
        m.msg_name = base + payload.toLong()
        m.msg_namelen = (if (send && array[start + payload].toInt() and 0xff == 2) 16 else UringOp.DATAGRAM_ADDRESS).toUInt()
        var rc = k_wheel_prep(ring, if (send) 1 else 0, fd, m.ptr, flags.toUInt(), slot.toULong())
        if (rc == -platform.posix.EBUSY) {
            // The submission queue is full: what is queued goes first.
            submit()
            rc = k_wheel_prep(ring, if (send) 1 else 0, fd, m.ptr, flags.toUInt(), slot.toULong())
        }
        if (rc != 0) return rc
        busy[slot] = true
        fds[slot] = fd
        pending++
        queued++
        return 0
    }

    /** The descriptor window [slot]'s request names. */
    fun fdOf(slot: Int): Int = fds[slot]

    /** One enter for every prepared SQE: the count submitted, or -errno. */
    fun submit(): Int {
        if (queued == 0) return 0
        val rc = k_wheel_enter(ring, 0u)
        if (rc >= 0) queued = maxOf(0, queued - rc)
        return rc
    }

    /**
     * Completions into [userData] and [res] from index [from] on, the window index and the result of each, without a
     * system call when enough are on the CQ already. With [minComplete] above zero it enters the kernel to wait for that
     * many (or for all that are pending, if fewer), submitting what is queued first. The count.
     */
    fun reap(minComplete: Int, userData: LongArray, res: IntArray, from: Int = 0): Int {
        val want = minOf(minComplete, pending)
        if (want > 0 && io_uring_cq_ready(ring).toInt() < want) {
            val rc = k_wheel_enter(ring, want.toUInt())
            if (rc >= 0) queued = maxOf(0, queued - rc)
        }
        val n = k_wheel_reap(ring, doneWindow, doneRes, minOf(windows, userData.size - from, res.size - from))
        for (i in 0 until n) {
            val w = doneWindow[i].toInt()
            if (w in 0 until windows && busy[w]) {
                busy[w] = false
                pending--
            }
            userData[from + i] = w.toLong()
            res[from + i] = doneRes[i]
        }
        return n
    }

    /** Waits for every request still pending, then frees the ring and the native records. */
    fun close() {
        val ud = LongArray(windows)
        val r = IntArray(windows)
        while (pending > 0) if (reap(1, ud, r) == 0) break
        for (w in 0 until windows) { pins[w]?.unpin(); pins[w] = null; bound[w] = null }
        io_uring_queue_exit(ring)
        nativeHeap.free(ring.rawValue)
        nativeHeap.free(msgs)
        nativeHeap.free(iovs)
        nativeHeap.free(doneWindow)
        nativeHeap.free(doneRes)
    }

    companion object {
        /** A wheel of [windows] windows on a new ring, or null when the kernel will not make the ring. */
        fun open(windows: Int): DatagramWheel? {
            require(windows > 0)
            val ring = nativeHeap.alloc<io_uring>()
            var rc = io_uring_queue_init(maxOf(8, windows).toUInt(), ring.ptr, SETUP_SUBMIT_ALL.toUInt())
            if (rc == -platform.posix.EINVAL) rc = io_uring_queue_init(maxOf(8, windows).toUInt(), ring.ptr, 0u)
            if (rc < 0) {
                nativeHeap.free(ring.ptr)
                return null
            }
            return DatagramWheel(windows, ring.ptr)
        }
    }
}
