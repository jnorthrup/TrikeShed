package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer

class WasmChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = WasmChannelHandle()
    override fun socket(domain: Int, type: Int, protocol: Int): Int = throw UnsupportedOperationException("ChannelOperations.socket unsupported on WASM")
    override fun bind(fd: Int, port: Int): Int = throw UnsupportedOperationException("ChannelOperations.bind unsupported on WASM")
    override fun listen(fd: Int, backlog: Int): Int = throw UnsupportedOperationException("ChannelOperations.listen unsupported on WASM")
    override fun accept(fd: Int): Int = throw UnsupportedOperationException("ChannelOperations.accept unsupported on WASM")
    override fun connect(fd: Int, host: String, port: Int): Int = throw UnsupportedOperationException("ChannelOperations.connect unsupported on WASM")
    override fun close(fd: Int): Int = throw UnsupportedOperationException("ChannelOperations.close unsupported on WASM")

    private class WasmChannelHandle : ChannelOperations.ChannelHandle {
        override val id: Int get() = 0
        override fun read(buffer: ByteBuffer, offset: Long): Int = throw UnsupportedOperationException("ChannelHandle.read unsupported on WASM")
        override fun write(buffer: ByteBuffer, offset: Long): Int = throw UnsupportedOperationException("ChannelHandle.write unsupported on WASM")
        override fun submit(): Int = throw UnsupportedOperationException("ChannelHandle.submit unsupported on WASM")
        override fun wait(minComplete: Int): List<ChannelResult> = throw UnsupportedOperationException("ChannelHandle.wait unsupported on WASM")
    }
}
