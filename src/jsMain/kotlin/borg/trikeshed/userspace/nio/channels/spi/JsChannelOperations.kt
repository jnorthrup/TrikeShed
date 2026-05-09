package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer

class JsChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = JsChannelHandle()
    override fun socket(domain: Int, type: Int, protocol: Int): Int = -1
    override fun bind(fd: Int, port: Int): Int = -1
    override fun listen(fd: Int, backlog: Int): Int = -1
    override fun accept(fd: Int): Int = -1

    private class JsChannelHandle : ChannelOperations.ChannelHandle {
        override val id: Int get() = 0
        override fun read(buffer: ByteBuffer, offset: Long): Int = -1
        override fun write(buffer: ByteBuffer, offset: Long): Int = -1
        override fun submit(): Int = 0
        override fun wait(minComplete: Int): List<ChannelResult> = emptyList()
    }
}
