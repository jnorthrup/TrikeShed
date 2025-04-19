package borg.trikeshed.reactor

interface BufferPool {
     fun acquire(): ByteBuffer
     fun release(buffer: ByteBuffer)
}

expect class PlatformIO {
     fun  createSelector(): SelectorInterface
     fun  createServerChannel(): ServerChannel
     fun  createClientChannel(): ClientChannel
        fun  createBufferPool(bufferSize: Int = 16384): BufferPool
    
    companion object {
         fun create(): PlatformIO
    }
}

expect interface ServerChannel : SelectableChannel {
      fun bind(port: Int)
      fun accept(): ClientChannel?
}

expect interface ClientChannel : SelectableChannel {
      fun connect(host: String, port: Int)
      fun read(buffer: ByteBuffer): Int
      fun write(buffer: ByteBuffer): Int
}
