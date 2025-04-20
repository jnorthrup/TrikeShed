package borg.trikeshed.reactor

interface ByteBuffer {
    fun clear()
    fun flip()
    fun hasRemaining(): Boolean
    fun remaining(): Int
    fun position(): Int
    fun position(newPosition: Int)
    fun put(byte: Byte)
    fun put(bytes: ByteArray)
    fun putInt(value: Int)
    fun putLong(value: Long)
    fun get(): Byte
    fun get(bytes: ByteArray)
    fun getInt(): Int
    fun getLong(): Long
    
    companion object {
        fun allocate(capacity: Int): ByteBuffer = ByteBufferFactory.allocate(capacity)
        fun allocateDirect(capacity: Int): ByteBuffer = ByteBufferFactory.allocateDirect(capacity)
        fun wrap(array: ByteArray): ByteBuffer = ByteBufferFactory.wrap(array)
    }
}

interface WritableChannel {
    suspend fun write(buffer: ByteBuffer): Int
    suspend fun close()
}

interface ReadableChannel {
    suspend fun read(buffer: ByteBuffer): Int
}

// Removed duplicate SelectableChannel interface definition here.
// The expect interface is defined in SelectableChannel.kt
expect class Selector {
    fun select(): Int
    fun selectedKeys(): MutableSet<SelectionKey>
    fun close()
}

typealias Interest = Int

class Operation(val interest: Interest, val action: () -> AsyncReaction?)


fun OP_READ(action: () -> AsyncReaction?): Operation = Operation(1 shl 0, action)
fun OP_WRITE(action: () -> AsyncReaction?): Operation = Operation(1 shl 2, action)
fun OP_CONNECT(action: () -> AsyncReaction?): Operation = Operation(1 shl 3, action)
fun OP_ACCEPT(action: () -> AsyncReaction?): Operation = Operation(1 shl 4, action)

interface AsyncReaction

interface UnaryAsyncReaction {
    suspend operator fun invoke(key: SelectionKey): AsyncReaction?
}

interface BufferPool {
    suspend fun acquire(): ByteBuffer
    suspend fun release(buffer: ByteBuffer)
}

interface ServerChannel : SelectableChannel {
    suspend fun bind(port: Int)
    suspend fun accept(): ClientChannel?
}

interface ClientChannel : SelectableChannel, ReadableChannel, WritableChannel {
    suspend fun connect(host: String, port: Int)
}
