package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer

class JsChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = JsChannelHandle()

    private class JsChannelHandle : ChannelOperations.ChannelHandle {
        override val id: Int get() = 0
        override fun read(buffer: ByteBuffer, offset: Long): Int = throw UnsupportedOperationException("ChannelHandle.read unsupported on JS")
        override fun write(buffer: ByteBuffer, offset: Long): Int = throw UnsupportedOperationException("ChannelHandle.write unsupported on JS")
        override fun submit(): Int = throw UnsupportedOperationException("ChannelHandle.submit unsupported on JS")
        override fun wait(minComplete: Int): List<ChannelResult> = throw UnsupportedOperationException("ChannelHandle.wait unsupported on JS")
    }
}
