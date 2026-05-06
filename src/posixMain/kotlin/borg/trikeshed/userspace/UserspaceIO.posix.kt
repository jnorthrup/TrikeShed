@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.open
import platform.posix.read
import platform.posix.socket
import platform.posix.write
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

internal actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {
        if (id >= 0) close(id)
    }
}

internal actual class ChannelImpl actual constructor(private val entries: Int) {
    private val pendingReads = mutableListOf<Triple<FileImpl, ByteBuffer, Long>>()
    private val pendingWrites = mutableListOf<Triple<FileImpl, ByteBuffer, Long>>()
    private val pendingCloses = mutableListOf<Pair<FileImpl, Long>>()
    private val results = mutableListOf<SelectionResult>()

    actual fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        pendingReads.add(Triple(file, buffer, userData))
    }

    actual fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        pendingWrites.add(Triple(file, buffer, userData))
    }

    actual fun accept(file: FileImpl, userData: Long) {}

    actual fun connect(file: FileImpl, address: String, port: Int, userData: Long) {}

    actual fun close(file: FileImpl, userData: Long) {
        pendingCloses.add(file to userData)
    }

    actual fun submit(): Int {
        var submitted = 0
        pendingReads.forEach { (file, buffer, userData) ->
            val bytes = buffer.array()
            val result = bytes.usePinned { pinned ->
                read(file.id, pinned.addressOf(0), bytes.size.toULong()).toInt()
            }
            results.add(SelectionResult(result, userData))
            submitted++
        }
        pendingWrites.forEach { (file, buffer, userData) ->
            val bytes = buffer.array()
            val result = bytes.usePinned { pinned ->
                write(file.id, pinned.addressOf(0), bytes.size.toULong()).toInt()
            }
            results.add(SelectionResult(result, userData))
            submitted++
        }
        pendingCloses.forEach { (file, userData) ->
            val result = if (file.id >= 0) close(file.id) else -1
            results.add(SelectionResult(result, userData))
            submitted++
        }
        pendingReads.clear()
        pendingWrites.clear()
        pendingCloses.clear()
        return submitted
    }

    actual fun wait(minComplete: Int): List<SelectionResult> {
        submit()
        return peek()
    }

    actual fun peek(): List<SelectionResult> = results.toList().also { results.clear() }
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return FileImpl(open(path, flags, 438u))
    }
}

internal actual object ChannelsImpl {
    actual fun open(entries: Int): ChannelImpl = ChannelImpl(entries)
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = FileImpl(socket(domain, type, protocol))
}
