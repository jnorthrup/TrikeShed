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

    fun handleFor(fd: Int): ChannelOperations.ChannelHandle = object : ChannelOperations.ChannelHandle {
        override val id: Int get() = fd
        override fun read(buffer: ByteBuffer, offset: Long): Int {
            val backing = buffer.array()
            val off = buffer.arrayOffset()
            return backing.usePinned { pread(fd, it.addressOf(off), buffer.remaining().convert(), offset) }.toInt()
        }
        override fun write(buffer: ByteBuffer, offset: Long): Int {
            val backing = buffer.array()
            val off = buffer.arrayOffset()
            return backing.usePinned { pwrite(fd, it.addressOf(off), buffer.remaining().convert(), offset) }.toInt()
        }
        override fun readv(fd: Int, buffer: ByteBuffer): Int {
            val backing = buffer.array()
            val off = buffer.arrayOffset()
            val remaining = buffer.remaining().convert()
            return memScoped {
                backing.usePinned { pinned ->
                    val iov = alloc<iovec>()
                    iov.iov_base = pinned.addressOf(off)
                    iov.iov_len = remaining
                    platform.posix.readv(fd, iov.ptr, 1).toInt()
                }
            }
        }
        override fun writev(fd: Int, buffer: ByteBuffer): Int {
            val backing = buffer.array()
            val off = buffer.arrayOffset()
            val remaining = buffer.remaining().convert()
            return memScoped {
                backing.usePinned { pinned ->
                    val iov = alloc<iovec>()
                    iov.iov_base = pinned.addressOf(off)
                    iov.iov_len = remaining
                    platform.posix.writev(fd, iov.ptr, 1).toInt()
                }
            }
        }
        override fun submit(): Int = 0
        override fun wait(minComplete: Int): List<ChannelResult> = emptyList()
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