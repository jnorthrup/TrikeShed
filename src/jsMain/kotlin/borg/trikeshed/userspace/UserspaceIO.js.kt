package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

private object JsFileRegistry {
    private var nextId = 1
    fun open(): FileImpl = FileImpl(nextId++)
}

internal actual class FileImpl actual constructor(actual val id: Int) {
    actual fun isOpen(): Boolean = id >= 0
    actual fun close() {}
}

internal actual class ChannelImpl actual constructor(private val entries: Int) {
    private val pendingResults = mutableListOf<SelectionResult>()

    actual fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        pendingResults.add(SelectionResult(-1, userData))
    }

    actual fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        pendingResults.add(SelectionResult(-1, userData))
    }

    actual fun accept(file: FileImpl, userData: Long) {
        pendingResults.add(SelectionResult(-1, userData))
    }

    actual fun connect(file: FileImpl, address: String, port: Int, userData: Long) {
        pendingResults.add(SelectionResult(-1, userData))
    }

    actual fun close(file: FileImpl, userData: Long) {
        pendingResults.add(SelectionResult(0, userData))
    }

    actual fun submit(): Int = pendingResults.size

    actual fun wait(minComplete: Int): List<SelectionResult> = peek()

    actual fun peek(): List<SelectionResult> = pendingResults.toList().also { pendingResults.clear() }
}

internal actual object FilesImpl {
    actual fun open(path: String, readOnly: Boolean): FileImpl = JsFileRegistry.open()
}

internal actual object ChannelsImpl {
    actual fun open(entries: Int): ChannelImpl = ChannelImpl(entries)
    actual fun socket(domain: Int, type: Int, protocol: Int): FileImpl = JsFileRegistry.open()
}
