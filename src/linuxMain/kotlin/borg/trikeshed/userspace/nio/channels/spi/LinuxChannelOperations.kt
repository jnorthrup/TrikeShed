@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.cinterop.*
import platform.posix.*

/** Linux primitives with channel submissions governed by the common uring facade. */
class LinuxChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = UringChannelHandle(entries)

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

    private class UringChannelHandle(entries: Int) : ChannelOperations.ChannelHandle {
        override val id: Int = -1
        private val facade = FunctionalUringFacade(entries, openUserspaceChannelBackend(entries))
        private var nextRequest = 0L
        private data class Request(val fd: Int, val userData: Long, val opcode: UringOp, val len: Int)
        private val pending = mutableMapOf<Long, Request>()

        private fun enqueue(sub: UringSubmission, userData: Long): Int {
            val request = nextRequest++
            facade.enqueue(sub.copy(userData = request))
            pending[request] = Request(sub.fd, userData, sub.opcode, sub.len)
            return 0
        }

        override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int =
            enqueue(UringSubmission(UringOp.READ, fd, 0, buffer.remaining(), -1, buffer = buffer), userData)

        override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int =
            enqueue(UringSubmission(UringOp.WRITE, fd, 0, buffer.remaining(), -1, buffer = buffer), userData)

        override fun prepAccept(serverFd: Int, userData: Long): Int =
            enqueue(UringSubmission(UringOp.ACCEPT, serverFd, 0, 0, 0), userData)

        override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int =
            enqueue(UringSubmission(UringOp.SENDMSG, fd, msgHdrPtr, 0, 0), userData)

        override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int =
            enqueue(UringSubmission(UringOp.RECVMSG, fd, msgHdrPtr, 0, 0), userData)

        // This handle owns a ring, not a file descriptor.
        override fun read(buffer: ByteBuffer, offset: Long): Int = -9
        override fun write(buffer: ByteBuffer, offset: Long): Int = -9
        override fun submit(): Int = facade.submit()

        override fun wait(minComplete: Int): List<ChannelResult> = facade.wait(minComplete).map { completion ->
            val request = checkNotNull(pending.remove(completion.userData)) { "foreign channel completion" }
            val result = when {
                completion.res == -11 -> 0
                completion.res == 0 && request.opcode == UringOp.READ && request.len > 0 -> -1
                else -> completion.res
            }
            ChannelResult(request.fd, result, request.userData)
        }

        override fun close() {
            facade.closeNow()
            pending.clear()
        }
    }
}
