package borg.trikeshed.reactor

import java.nio.channels.ServerSocketChannel as JvmServerSocketChannel
import java.nio.channels.SocketChannel as JvmSocketChannel
import java.nio.channels.Selector as JvmSelector
import java.nio.channels.SelectionKey as JvmSelectionKey
import java.net.InetSocketAddress
import java.nio.ByteBuffer as JvmByteBuffer

actual class PlatformIO {
    actual suspend fun createSelector(): SelectorInterface {
        return JvmSelectorImpl(JvmSelector.open())
    }

    actual suspend fun createServerChannel(): ServerChannel {
        return JvmServerChannelImpl(JvmServerSocketChannel.open())
    }

    actual suspend fun createClientChannel(): ClientChannel {
        return JvmClientChannelImpl(JvmSocketChannel.open())
    }

    actual suspend fun createBufferPool(bufferSize: Int): BufferPool {
        return JvmBufferPool(bufferSize)
    }

    actual companion object {
        actual suspend fun create(): PlatformIO = PlatformIO()
    }
}

private class JvmBufferPool(private val bufferSize: Int) : BufferPool {
    private val pool = ArrayDeque<JvmByteBuffer>()
    
    override suspend fun acquire(): ByteBuffer {
        val buffer = synchronized(pool) {
            pool.removeLastOrNull() ?: JvmByteBuffer.allocateDirect(bufferSize)
        }
        return JvmByteBufferWrapper(buffer)
    }
    
    override suspend fun release(buffer: ByteBuffer) {
        if (buffer is JvmByteBufferWrapper) {
            buffer.underlying.clear()
            synchronized(pool) {
                pool.addLast(buffer.underlying)
            }
        }
    }
}

private class JvmByteBufferWrapper(val underlying: JvmByteBuffer) : ByteBuffer {
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

    companion object {
        fun allocate(capacity: Int): ByteBuffer = JvmByteBufferWrapper(JvmByteBuffer.allocate(capacity))
        fun allocateDirect(capacity: Int): ByteBuffer = JvmByteBufferWrapper(JvmByteBuffer.allocateDirect(capacity))
        fun wrap(array: ByteArray): ByteBuffer = JvmByteBufferWrapper(JvmByteBuffer.wrap(array))
    }
}

private class JvmSelectorImpl(private val selector: JvmSelector) : SelectorInterface {
    private val keyMap = mutableMapOf<JvmSelectionKey, SelectionKeyImpl>()
    
    override suspend fun select(): Int = selector.select()
    override suspend fun wakeup() { selector.wakeup() }
    
    override suspend fun register(channel: SelectableChannel, ops: Int, attachment: Any?): SelectionKey {
        val jvmChannel = when(channel) {
            is JvmServerChannelImpl -> channel.underlying
            is JvmClientChannelImpl -> channel.underlying
            else -> throw IllegalArgumentException("Unknown channel type")
        }
        
        val jvmKey = jvmChannel.register(selector, ops, attachment)
        return keyMap.getOrPut(jvmKey) { SelectionKeyImpl(jvmKey, channel) }
    }
    
    override suspend fun selectedKeys(): Set<SelectionKey> {
        return selector.selectedKeys().mapNotNull { jvmKey ->
            keyMap[jvmKey]
        }.toSet()
    }
    
    override suspend fun close() = selector.close()
}

private class SelectionKeyImpl(
    private val underlying: JvmSelectionKey,
    private val platformChannel: SelectableChannel
) : SelectionKey() {
    override val isValid: Boolean get() = underlying.isValid()
    override val readyOps: Int get() = underlying.readyOps()
    override var interestOps: Int
        get() = underlying.interestOps()
        set(value) { underlying.interestOps(value) }
    override var attachment: Any?
        get() = underlying.attachment()
        set(value) { underlying.attach(value) }
    override fun cancel() = underlying.cancel()
    override fun channel(): SelectableChannel = platformChannel
}

private class JvmServerChannelImpl(val underlying: JvmServerSocketChannel) : ServerChannel {
    override suspend fun bind(port: Int) {
        underlying.bind(InetSocketAddress(port))
    }
    
    override suspend fun accept(): ClientChannel? {
        return underlying.accept()?.let { JvmClientChannelImpl(it) }
    }

    override suspend fun configureBlocking(block: Boolean): SelectableChannel {
        underlying.configureBlocking(block)
        return this
    }

    override suspend fun register(selector: SelectorInterface, ops: Int, attachment: Any?): SelectionKey {
        require(selector is JvmSelectorImpl) { "Selector must be JVM implementation" }
        return selector.register(this, ops, attachment)
    }

    override suspend fun close() = underlying.close()
}

private class JvmClientChannelImpl(val underlying: JvmSocketChannel) : ClientChannel {
    override suspend fun connect(host: String, port: Int) {
        underlying.connect(InetSocketAddress(host, port))
    }
    
    override suspend fun read(buffer: ByteBuffer): Int {
        require(buffer is JvmByteBufferWrapper) { "Buffer must be JVM implementation" }
        return underlying.read(buffer.underlying)
    }
    
    override suspend fun write(buffer: ByteBuffer): Int {
        require(buffer is JvmByteBufferWrapper) { "Buffer must be JVM implementation" }
        return underlying.write(buffer.underlying)
    }

    override suspend fun configureBlocking(block: Boolean): SelectableChannel {
        underlying.configureBlocking(block)
        return this
    }

    override suspend fun register(selector: SelectorInterface, ops: Int, attachment: Any?): SelectionKey {
        require(selector is JvmSelectorImpl) { "Selector must be JVM implementation" }
        return selector.register(this, ops, attachment)
    }

    override suspend fun close() = underlying.close()
}
