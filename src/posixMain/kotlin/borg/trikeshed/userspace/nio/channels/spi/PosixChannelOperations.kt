@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.cinterop.*
import platform.posix.*

class PosixChannelOperations : ChannelOperations {

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = PosixChannelHandle(entries)

    override fun socket(domain: Int, type: Int, protocol: Int): Int = platform.posix.socket(domain, type, protocol)

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
