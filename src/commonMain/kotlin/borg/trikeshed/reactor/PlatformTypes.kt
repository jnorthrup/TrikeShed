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

interface SelectableChannel {
    suspend fun configureBlocking(block: Boolean): SelectableChannel
    suspend fun register(selector: SelectorInterface, ops: Int, attachment: Any?): SelectionKey
    suspend fun close()
}

expect class Selector {
    fun select(): Int
    fun selectedKeys(): MutableSet<SelectionKey>
    fun close()
}

typealias Interest = Int
const val OP_READ: Interest = 1 shl 0
const val OP_WRITE: Interest = 1 shl 2
const val OP_CONNECT: Interest = 1 shl 3
const val OP_ACCEPT: Interest = 1 shl 4
