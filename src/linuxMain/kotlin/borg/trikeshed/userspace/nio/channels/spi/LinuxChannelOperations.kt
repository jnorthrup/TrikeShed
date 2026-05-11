@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.Liburing
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.cinterop.*
import platform.posix.*

/**
 * Linux io_uring-backed [ChannelOperations].
 *
 * Socket/bind/listen/accept/connect use POSIX syscalls directly (same semantics
 * as [PosixChannelOperations]). File/socket read-write and accept SQEs are queued
 * through the [Liburing] global ring and flushed via [ChannelHandle.submit].
 *
 * A monotonic per-operation request ID is used as the io_uring userData.  The
 * caller-supplied [userData] is stored alongside the pinned buffer in [pendingOps]
 * and echoed back in [ChannelResult.userData] on completion, preserving the
 * caller's correlation token while keeping io_uring userData unique.
 */
class LinuxChannelOperations : ChannelOperations {

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle {
        Liburing.open(entries).getOrThrow()
        return UringChannelHandle()
    }

    override fun socket(domain: Int, type: Int, protocol: Int): Int =
        platform.posix.socket(domain, type, protocol)

    override fun bind(fd: Int, port: Int): Int = memScoped {
        val addr = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_port = ((port ushr 8) or ((port and 0xFF) shl 8)).convert()
            sin_addr.s_addr = INADDR_ANY.convert()
        }
        platform.posix.bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()).toInt()
    }

    override fun listen(fd: Int, backlog: Int): Int = platform.posix.listen(fd, backlog)

    override fun accept(fd: Int): Int = platform.posix.accept(fd, null, null)

    override fun connect(fd: Int, host: String, port: Int): Int = memScoped {
        val addr = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_port = ((port ushr 8) or ((port and 0xFF) shl 8)).toUShort().convert()
        }
        val he = gethostbyname(host) ?: return -1
        val addrList = he.pointed.h_addr_list ?: return -1
        memcpy(addr.sin_addr.ptr, addrList[0]!!, 4u)
        platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
    }

    override fun close(fd: Int): Int = platform.posix.close(fd)

    // ── UringChannelHandle ────────────────────────────────────────────────────

    private inner class UringChannelHandle : ChannelOperations.ChannelHandle {
        override val id: Int = -1

        /** Monotonic ID issued per SQE so io_uring userData is always unique. */
        private var nextReqId: Long = 0L

        /** In-flight operations: ioReqId → (outerUserData, fd, pinned buffer or null). */
        private val pendingOps = mutableMapOf<Long, PendingOp>()

        private data class PendingOp(
            val outerUserData: Long,
            val fd: Int,
            val pin: Pinned<ByteArray>?,
        )

        // ── SQE preparation ───────────────────────────────────────────────

        override fun prepAccept(serverFd: Int, userData: Long): Int {
            val rid = nextReqId++
            pendingOps[rid] = PendingOp(userData, serverFd, null)
            return Liburing.prepAccept(serverFd, rid).fold({ 0 }, {
                pendingOps.remove(rid); -1
            })
        }

        override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            val rid = nextReqId++
            val off = buffer.arrayOffset() + buffer.position()
            val pin = buffer.array().pin()
            pendingOps[rid] = PendingOp(userData, fd, pin)
            val addr = pin.addressOf(off).rawValue
            return Liburing.prepRead(fd, addr, buffer.remaining(), 0L, rid).fold({ 0 }, {
                pendingOps.remove(rid)?.pin?.unpin(); -1
            })
        }

        override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            val rid = nextReqId++
            val off = buffer.arrayOffset() + buffer.position()
            val pin = buffer.array().pin()
            pendingOps[rid] = PendingOp(userData, fd, pin)
            val addr = pin.addressOf(off).rawValue
            return Liburing.prepWrite(fd, addr, buffer.remaining(), 0L, rid).fold({ 0 }, {
                pendingOps.remove(rid)?.pin?.unpin(); -1
            })
        }

        // ── Synchronous pread/pwrite (file I/O) ───────────────────────────

        override fun read(buffer: ByteBuffer, offset: Long): Int {
            val off = buffer.arrayOffset() + buffer.position()
            return buffer.array().usePinned {
                pread(id, it.addressOf(off), buffer.remaining().convert(), offset)
            }.toInt()
        }

        override fun write(buffer: ByteBuffer, offset: Long): Int {
            val off = buffer.arrayOffset() + buffer.position()
            return buffer.array().usePinned {
                pwrite(id, it.addressOf(off), buffer.remaining().convert(), offset)
            }.toInt()
        }

        // ── Submit / wait ─────────────────────────────────────────────────

        override fun submit(): Int = Liburing.submit().getOrElse { -1 }

        override fun wait(minComplete: Int): List<ChannelResult> {
            val results = mutableListOf<ChannelResult>()
            repeat(minComplete) {
                val c = Liburing.waitCqe().getOrNull() ?: return results
                val op = pendingOps.remove(c.userData)
                op?.pin?.unpin()
                results.add(ChannelResult(op?.fd ?: -1, c.res, op?.outerUserData ?: c.userData))
            }
            // Drain any additional ready completions without blocking.
            while (true) {
                val c = Liburing.peekCqe().getOrNull() ?: break
                val op = pendingOps.remove(c.userData)
                op?.pin?.unpin()
                results.add(ChannelResult(op?.fd ?: -1, c.res, op?.outerUserData ?: c.userData))
            }
            return results
        }
    }
}
