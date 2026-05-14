package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.spi.FileImpl
import borg.trikeshed.userspace.nio.spi.UserspaceChannelBackend

private object WasmFileRegistry {
    private var nextId = 1
    fun open(): FileImpl = FileImpl(nextId++)
}

private class WasmUserspaceChannelBackend : UserspaceChannelBackend {
    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int = -1
    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int = -1
    override fun accept(file: FileImpl): Int = -1
    override fun connect(file: FileImpl, address: CharSequence, port: Int): Int = -1
    override fun close(file: FileImpl): Int = 0
    override fun sync(file: FileImpl, metaData: Boolean): Int = 0
    override fun truncate(file: FileImpl, size: Long): Int = 0
    override fun map(file: FileImpl, mode: CharSequence, position: Long, size: Long): Int = -1
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = WasmUserspaceChannelBackend()

actual class FileImpl actual constructor(actual val id: Int) {
    @PublishedApi internal var path: CharSequence = ""
    @PublishedApi internal var knownSize: Long = -1L
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {}
    actual fun size(): Long = knownSize
}

internal actual object FilesImpl {
    actual fun open(path: CharSequence, readOnly: Boolean): FileImpl =
        WasmFileRegistry.open().also { it.path = path }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = WasmFileRegistry.open()
}
