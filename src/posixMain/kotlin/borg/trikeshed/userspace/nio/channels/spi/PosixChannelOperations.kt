@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.cinterop.*
import platform.posix.*

class PosixChannelOperations : ChannelOperations {

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(entries)

    override fun socket(domain: Int, type: Int, protocol: Int): Int = platform.posix.socket(domain, type, protocol)

    override fun bind(fd: Int, port: Int): Int = memScoped {
        val addr = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_port = ((port ushr 8) or ((port and 0xFF) shl 8)).convert()  // htons inline
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

    fun handleFor(fd: Int): ChannelOperations.ChannelHandle = RingHandle(fd)

    private class RingHandle(private val fd: Int) : ChannelOperations.ChannelHandle {
        override val id: Int get() = fd
        private data class PendingOp(val f: Int, val buf: ByteBuffer, val read: Boolean, val user: Long)
        private val pending = mutableListOf<PendingOp>()
        private var lastResults: List<ChannelResult> = emptyList()

        override fun read(buffer: ByteBuffer, offset: Long): Int {
            val off = buffer.arrayOffset()
            return buffer.array().usePinned { pread(fd, it.addressOf(off), buffer.remaining().convert(), offset) }.toInt()
        }
        override fun write(buffer: ByteBuffer, offset: Long): Int {
            val off = buffer.arrayOffset()
            return buffer.array().usePinned { pwrite(fd, it.addressOf(off), buffer.remaining().convert(), offset) }.toInt()
        }
        override fun readv(fd: Int, buffer: ByteBuffer): Int {
            pending.add(PendingOp(fd, buffer, read = true, user = 0L))
            return 0
        }
        override fun writev(fd: Int, buffer: ByteBuffer): Int {
            pending.add(PendingOp(fd, buffer, read = false, user = 0L))
            return 0
        }
        override fun submit(): Int {
            val completions = mutableListOf<ChannelResult>()
            for (op in pending) {
                val off = op.buf.arrayOffset()
                val remaining = op.buf.remaining()
                val backing = op.buf.array()
                val f = op.f; val rd = op.read; val u = op.user
                val res = backing.usePinned { pinned ->
                    (if (rd) recv(f, pinned.addressOf(off), remaining.convert(), 0)
                     else send(f, pinned.addressOf(off), remaining.convert(), 0)).toInt()
                }
                completions.add(ChannelResult(f, res, u))
            }
            val n = pending.size
            pending.clear()
            lastResults = completions
            return n
        }
        override fun wait(minComplete: Int): List<ChannelResult> = lastResults
    }

    private class PosixChannelHandle(private val entries: Int) : ChannelOperations.ChannelHandle {
        override val id: Int get() = 0
        override fun read(buffer: ByteBuffer, offset: Long): Int {
            val backing = buffer.array()
            val off = buffer.arrayOffset()
            return backing.usePinned { pread(id, it.addressOf(off), buffer.remaining().convert(), offset) }.toInt()
        }
        override fun write(buffer: ByteBuffer, offset: Long): Int {
            val backing = buffer.array()
            val off = buffer.arrayOffset()
            return backing.usePinned { pwrite(id, it.addressOf(off), buffer.remaining().convert(), offset) }.toInt()
        }
        override fun submit(): Int = 0
        override fun wait(minComplete: Int): List<ChannelResult> = emptyList()
    }
}