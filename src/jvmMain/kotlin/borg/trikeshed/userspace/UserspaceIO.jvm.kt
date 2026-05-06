package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import java.nio.channels.FileChannel as JvmFileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

private object JvmFileRegistry {
    private var nextId = 1
    private val channels = mutableMapOf<Int, JvmFileChannel>()

    fun open(path: String, readOnly: Boolean): FileImpl {
        val options = if (readOnly) {
            setOf(StandardOpenOption.READ)
        } else {
            setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        }
        val id = nextId++
        channels[id] = JvmFileChannel.open(Paths.get(path), options)
        return FileImpl(id)
    }

    fun socket(): FileImpl = FileImpl(-1)

    fun channel(id: Int): JvmFileChannel? = channels[id]

    fun isOpen(id: Int): Boolean = channels[id]?.isOpen ?: (id >= 0)

    fun close(id: Int) {
        channels.remove(id)?.close()
    }
}

private class JvmUserspaceChannelBackend : UserspaceChannelBackend {
    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val channel = JvmFileRegistry.channel(file.id) ?: return -1
        val nioBuffer = java.nio.ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
        val result = runCatching { channel.read(nioBuffer, offset) }.getOrElse { -1 }
        if (result > 0) buffer.position(buffer.position() + result)
        return result
    }

    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val channel = JvmFileRegistry.channel(file.id) ?: return -1
        val nioBuffer = java.nio.ByteBuffer.wrap(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
        val result = runCatching { channel.write(nioBuffer, offset) }.getOrElse { -1 }
        if (result > 0) buffer.position(buffer.position() + result)
        return result
    }

    override fun accept(file: FileImpl): Int = -1

    override fun connect(file: FileImpl, address: String, port: Int): Int = -1

    override fun close(file: FileImpl): Int = runCatching {
        file.close()
        0
    }.getOrElse { -1 }
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = JvmUserspaceChannelBackend()

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = JvmFileRegistry.isOpen(id)
    actual fun close() = JvmFileRegistry.close(id)
}

internal actual class ChannelImpl actual constructor(entries: Int) {
    private val facade = FunctionalUringFacade(entries, openUserspaceChannelBackend(entries))

    actual fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        facade.read(file, buffer, offset, userData)
    }

    actual fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        facade.write(file, buffer, offset, userData)
    }

    actual fun accept(file: FileImpl, userData: Long) {
        facade.accept(file, userData)
    }

    actual fun connect(file: FileImpl, address: String, port: Int, userData: Long) {
        facade.connect(file, address, port, userData)
    }

    actual fun close(file: FileImpl, userData: Long) {
        facade.close(file, userData)
    }

    actual fun submit(): Int = facade.submit()

    actual fun wait(minComplete: Int): List<SelectionResult> = facade.wait(minComplete)

    actual fun peek(): List<SelectionResult> = facade.peek()
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl = JvmFileRegistry.open(path, readOnly)
}

internal actual object ChannelsImpl {
    actual fun open(entries: Int): ChannelImpl = ChannelImpl(entries)
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = JvmFileRegistry.socket()
}
