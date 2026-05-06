package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

private object JvmFileRegistry {
    private var nextId = 1
    private val channels = mutableMapOf<Int, FileChannel>()

    fun open(path: String, readOnly: Boolean): FileImpl {
        val options = if (readOnly) {
            setOf(StandardOpenOption.READ)
        } else {
            setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        }
        val id = nextId++
        channels[id] = FileChannel.open(Paths.get(path), options)
        return FileImpl(id)
    }

    fun socket(): FileImpl = FileImpl(-1)

    fun channel(id: Int): FileChannel? = channels[id]

    fun isOpen(id: Int): Boolean = channels[id]?.isOpen ?: id >= 0

    fun close(id: Int) {
        channels.remove(id)?.close()
    }
}

internal actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = JvmFileRegistry.isOpen(id)
    actual fun close() = JvmFileRegistry.close(id)
}

internal actual class ChannelImpl actual constructor(private val entries: Int) {
    private val pendingResults = mutableListOf<SelectionResult>()
    private val preparedOps = mutableListOf<() -> Unit>()

    actual fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        preparedOps.add {
            val channel = JvmFileRegistry.channel(file.id)
            val result = try {
                channel?.read(java.nio.ByteBuffer.wrap(buffer.array()), offset) ?: -1
            } catch (_: Throwable) {
                -1
            }
            synchronized(pendingResults) { pendingResults.add(SelectionResult(result, userData)) }
        }
    }

    actual fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        preparedOps.add {
            val channel = JvmFileRegistry.channel(file.id)
            val result = try {
                channel?.write(java.nio.ByteBuffer.wrap(buffer.array()), offset) ?: -1
            } catch (_: Throwable) {
                -1
            }
            synchronized(pendingResults) { pendingResults.add(SelectionResult(result, userData)) }
        }
    }

    actual fun accept(file: FileImpl, userData: Long) {
        synchronized(pendingResults) { pendingResults.add(SelectionResult(-1, userData)) }
    }

    actual fun connect(file: FileImpl, address: String, port: Int, userData: Long) {
        synchronized(pendingResults) { pendingResults.add(SelectionResult(-1, userData)) }
    }

    actual fun close(file: FileImpl, userData: Long) {
        preparedOps.add {
            val result = try {
                file.close()
                0
            } catch (_: Throwable) {
                -1
            }
            synchronized(pendingResults) { pendingResults.add(SelectionResult(result, userData)) }
        }
    }

    actual fun submit(): Int {
        val count = preparedOps.size
        preparedOps.forEach { it() }
        preparedOps.clear()
        return count
    }

    actual fun wait(minComplete: Int): List<SelectionResult> = peek()

    actual fun peek(): List<SelectionResult> =
        synchronized(pendingResults) {
            pendingResults.toList().also { pendingResults.clear() }
        }
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl = JvmFileRegistry.open(path, readOnly)
}

internal actual object ChannelsImpl {
    actual fun open(entries: Int): ChannelImpl = ChannelImpl(entries)
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = JvmFileRegistry.socket()
}
