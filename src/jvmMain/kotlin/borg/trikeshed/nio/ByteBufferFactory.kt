package borg.trikeshed.nio

public class JvmByteBufferWrapper(val underlying: java.nio.ByteBuffer) : ByteBuffer {
    override fun clear() { underlying.clear() }
    override fun flip() { underlying.flip() }
    override fun hasRemaining(): Boolean = underlying.hasRemaining()
    override fun remaining(): Int = underlying.remaining()
    override fun position(): Int = underlying.position()
    override fun position(newPosition: Int) { underlying.position(newPosition) }
    override fun put(byte: Byte) { underlying.put(byte) }
    override fun put(bytes: ByteArray) { underlying.put(bytes) }
    override fun putInt(value: Int) { underlying.putInt(value) }
    override fun putLong(value: Long) { underlying.putLong(value) }
    override fun get(): Byte = underlying.get()
    override fun get(bytes: ByteArray) { underlying.get(bytes) }
    override fun getInt(): Int = underlying.getInt()
    override fun getLong(): Long = underlying.getLong()
    override fun limit(): Int = underlying.limit()
    override fun limit(newLimit: Int) { underlying.limit(newLimit) }
    override fun capacity(): Int = underlying.capacity()
}

actual object ByteBufferFactory {
    actual fun allocate(capacity: Int): ByteBuffer = 
        JvmByteBufferWrapper(java.nio.ByteBuffer.allocate(capacity))
    
    actual fun allocateDirect(capacity: Int): ByteBuffer = 
        JvmByteBufferWrapper(java.nio.ByteBuffer.allocateDirect(capacity))
    
    actual fun wrap(array: ByteArray): ByteBuffer = 
        JvmByteBufferWrapper(java.nio.ByteBuffer.wrap(array))
}
