@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.PosixUringIO
import borg.trikeshed.userspace.nio.ByteBuffer
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.open
import platform.posix.socket

private class PosixUserspaceChannelBackend(
    private val entries: Int,
) : UserspaceChannelBackend {
    override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val bytes = buffer.array()
        val start = buffer.arrayOffset() + buffer.position()
        val length = buffer.remaining()
        if (length <= 0) return 0
        val result = PosixUringIO.readAt(file.id, bytes, start, length, offset, entries)
        if (result > 0) buffer.position(buffer.position() + result)
        return result
    }

    override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
        val bytes = buffer.array()
        val start = buffer.arrayOffset() + buffer.position()
        val length = buffer.remaining()
        if (length <= 0) return 0
        val result = PosixUringIO.writeAt(file.id, bytes, start, length, offset, entries)
        if (result > 0) buffer.position(buffer.position() + result)
        return result
    }

    override fun accept(file: FileImpl): Int = -1

    override fun connect(file: FileImpl, address: String, port: Int): Int = -1

    override fun close(file: FileImpl): Int = PosixUringIO.closeFd(file.id, entries)
}

internal actual fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend = PosixUserspaceChannelBackend(entries)

actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {
        if (id >= 0) PosixUringIO.closeFd(id)
    }
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
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return FileImpl(open(path, flags, 438u))
    }
}

internal actual object ChannelsImpl {
    actual fun open(entries: Int): ChannelImpl = ChannelImpl(entries)
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(platform.posix.socket(domain, type, protocol))
}
