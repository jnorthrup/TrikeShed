@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.cinterop.*
import platform.posix.*

class PosixChannelOperations : ChannelOperations {

    // Track which fds have completed connect (for EINPROGRESS handling)
    private val connected = mutableSetOf<Int>()

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = RingHandle(-1)

    override fun socket(domain: Int, type: Int, protocol: Int): Int = platform.posix.socket(domain, type, protocol)

    override fun bind(fd: Int, port: Int): Int = memScoped {
        val addr = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_port = ((port ushr 8) or ((port and 0xFF) shl 8)).convert()
            sin_addr.s_addr = INADDR_ANY.convert()
        }
        platform.posix.bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()).toInt()
    }

    override fun listen(fd: Int, backlog: Int): Int {
        platform.posix.fcntl(fd, platform.posix.F_SETFL, platform.posix.fcntl(fd, platform.posix.F_GETFL) or platform.posix.O_NONBLOCK)
        return platform.posix.listen(fd, backlog)
    }

    override fun accept(fd: Int): Int = platform.posix.accept(fd, null, null)

    override fun connect(fd: Int, host: CharSequence, port: Int): Int = memScoped {
        val addr = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_port = ((port ushr 8) or ((port and 0xFF) shl 8)).toUShort().convert()
        }
        val he = gethostbyname(host.toString()) ?: return@memScoped -1
        val addrList = he.pointed.h_addr_list ?: return@memScoped -1
        memcpy(addr.sin_addr.ptr, addrList[0]!!, 4u)
        val res = platform.posix.connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()).toInt()
        if (res < 0 && platform.posix.errno == platform.posix.EINPROGRESS) {
            val pfds = platform.posix.alloc<pollfd>()
            pfds.fd = fd
            pfds.events = POLLOUT.toShort()
            val pr = platform.posix.poll(pfds.ptr, 1u, 15000)
            if (pr <= 0) return@memScoped -1
            connected.add(fd)
            return@memScoped 0
        }
        if (res == 0) connected.add(fd)
        return@memScoped res
    }

    override fun close(fd: Int): Int {
        connected.remove(fd)
        return platform.posix.close(fd)
    }

    fun markConnected(fd: Int) { connected.add(fd) }

    private inner class RingHandle(private val targetFd: Int) : ChannelOperations.ChannelHandle {
        override val id: Int get() = targetFd

        override fun read(buffer: ByteBuffer, offset: Long): Int {
            val off = buffer.arrayOffset() + buffer.position()
            return buffer.array().usePinned { pread(targetFd, it.addressOf(off), buffer.remaining().convert(), offset) }.toInt()
        }
        override fun write(buffer: ByteBuffer, offset: Long): Int {
            val off = buffer.arrayOffset() + buffer.position()
            return buffer.array().usePinned { pwrite(targetFd, it.addressOf(off), buffer.remaining().convert(), offset) }.toInt()
        }
        override fun readv(fd: Int, buf: ByteBuffer, userData: Long): Int {
            val off = buf.arrayOffset() + buf.position()
            val backing = buf.array()
            val n = backing.usePinned { pinned ->
                platform.posix.recv(fd, pinned.addressOf(off), buf.remaining().convert(), 0)
            }.toInt()
            if (n > 0) buf.position(buf.position() + n)
            return n
        }
        override fun writev(fd: Int, buf: ByteBuffer, userData: Long): Int {
            val off = buf.arrayOffset() + buf.position()
            val backing = buf.array()
            val n = backing.usePinned { pinned ->
                platform.posix.send(fd, pinned.addressOf(off), buf.remaining().convert(), 0)
            }.toInt()
            if (n > 0) buf.position(buf.position() + n)
            return n
        }
        override fun submit(): Int = 0
        override fun wait(minComplete: Int): List<ChannelResult> = emptyList()
    }
}
