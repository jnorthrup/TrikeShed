package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

private class JvmStubChannelBackend : UserspaceChannelBackend {
    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int = -1
    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int = -1
    override fun accept(file: FileImpl): Int = -1
    override fun connect(file: FileImpl, address: String, port: Int): Int = -1
    override fun close(file: FileImpl): Int = 0
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = JvmStubChannelBackend()

actual class FileImpl actual constructor(actual val id: Int) {
    @PublishedApi internal var path: String = ""
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {}
    actual fun size(): Long = java.io.File(path).let { if (it.exists()) it.length() else -1L }
}

internal actual class ChannelImpl actual constructor(entries: Int) {
    private val facade = FunctionalUringFacade(entries, openUserspaceChannelBackend(entries))

    actual fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        facade.read(file, buffer, offset, userData)
    }
    actual fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        facade.write(file, buffer, offset, userData)
    }
    actual fun accept(file: FileImpl, userData: Long) { facade.accept(file, userData) }
    actual fun connect(file: FileImpl, address: String, port: Int, userData: Long) { facade.connect(file, address, port, userData) }
    actual fun close(file: FileImpl, userData: Long) { facade.close(file, userData) }
    actual fun submit(): Int = facade.submit()
    actual fun wait(minComplete: Int): List<SelectionResult> = facade.wait(minComplete)
    actual fun peek(): List<SelectionResult> = facade.peek()
}

internal actual object FilesImpl {
    private var nextId = 1
    actual fun open(path: String, readOnly: Boolean): FileImpl =
        FileImpl(nextId++).also { it.path = path }
}

internal actual object ChannelsImpl {
    actual fun open(entries: Int): ChannelImpl = ChannelImpl(entries)
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(-1)
}
