package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer

class JsChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = JsChannelHandle()
    override fun socket(domain: Int, type: Int, protocol: Int): Int = throw UnsupportedOperationException("ChannelOperations.socket unsupported on JS")
    override fun bind(fd: Int, port: Int): Int = throw UnsupportedOperationException("ChannelOperations.bind unsupported on JS")
    override fun listen(fd: Int, backlog: Int): Int = throw UnsupportedOperationException("ChannelOperations.listen unsupported on JS")
    override fun accept(fd: Int): Int = throw UnsupportedOperationException("ChannelOperations.accept unsupported on JS")
    override fun connect(fd: Int, host: String, port: Int): Int = throw UnsupportedOperationException("ChannelOperations.connect unsupported on JS")
    override fun close(fd: Int): Int = throw UnsupportedOperationException("ChannelOperations.close unsupported on JS")

    private class JsChannelHandle : ChannelOperations.ChannelHandle {
        override val id: Int get() = 0
        override fun read(buffer: ByteBuffer, offset: Long): Int = throw UnsupportedOperationException("ChannelHandle.read unsupported on JS")
        override fun write(buffer: ByteBuffer, offset: Long): Int = throw UnsupportedOperationException("ChannelHandle.write unsupported on JS")
        override fun submit(): Int = throw UnsupportedOperationException("ChannelHandle.submit unsupported on JS")
        override fun wait(minComplete: Int): List<ChannelResult> = throw UnsupportedOperationException("ChannelHandle.wait unsupported on JS")
    }
}
