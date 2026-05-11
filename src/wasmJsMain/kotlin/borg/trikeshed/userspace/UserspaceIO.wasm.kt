package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

private object WasmFileRegistry {
    private var nextId = 1
    fun open(): FileImpl = FileImpl(nextId++)
}

private class WasmUserspaceChannelBackend : UserspaceChannelBackend {
    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int = -1
    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int = -1
    override fun accept(file: FileImpl): Int = -1
    override fun connect(file: FileImpl, address: String, port: Int): Int = -1
    override fun close(file: FileImpl): Int = 0
    override fun sync(file: FileImpl, metaData: Boolean): Int = 0
    override fun truncate(file: FileImpl, size: Long): Int = 0
    override fun map(file: FileImpl, mode: String, position: Long, size: Long): Int = -1
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = WasmUserspaceChannelBackend()

actual class FileImpl actual constructor(actual val id: Int) {
    @PublishedApi internal var path: String = ""
    @PublishedApi internal var knownSize: Long = -1L
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {}
    actual fun size(): Long = knownSize
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl =
        WasmFileRegistry.open().also { it.path = path }
}

internal actual object ChannelsImpl {
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = WasmFileRegistry.open()
}