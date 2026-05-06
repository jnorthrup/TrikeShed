package borg.trikeshed.userspace

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.userspace.nio.ByteBuffer

data class SelectionResult(val res: Int, val userData: Long)

class File internal constructor(internal val impl: FileImpl) {
    val id: Int get() = impl.id
    fun isOpen(): Boolean = impl.isOpen()
    fun close() = impl.close()
}

class Channel internal constructor(private val impl: ChannelImpl) {
    fun read(file: File, buffer: ByteBuffer, offset: Long, userData: Long) =
        impl.read(file.impl, buffer, offset, userData)

    fun write(file: File, buffer: ByteBuffer, offset: Long, userData: Long) =
        impl.write(file.impl, buffer, offset, userData)

    fun accept(file: File, userData: Long) =
        impl.accept(file.impl, userData)

    fun connect(file: File, address: String, port: Int, userData: Long) =
        impl.connect(file.impl, address, port, userData)

    fun close(file: File, userData: Long) =
        impl.close(file.impl, userData)

    fun submit(): Int = impl.submit()

    fun wait(minComplete: Int = 1): List<SelectionResult> = impl.wait(minComplete)

    fun peek(): List<SelectionResult> = impl.peek()
}

object Files {
    fun open(path: String, readOnly: Boolean = true): File = File(FilesImpl.open(path, readOnly))
}

object Channels {
    fun open(entries: Int = 256): Channel = Channel(ChannelsImpl.open(entries))

    fun socket(domain: Int, type: Int, protocol: Int): File =
        File(ChannelsImpl.socket(domain, type, protocol))

    fun wrap(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)
    fun wrapRegion(bytes: ByteArray): ByteRegion = ByteRegion.wrap(bytes)
    fun region(buffer: ByteBuffer): ByteRegion = ByteRegion(buffer)
    fun series(buffer: ByteBuffer): ByteSeries = buffer.asByteSeries()
}

expect class FileImpl(id: Int) {
    val id: Int
    fun isOpen(): Boolean
    fun close()
}

internal expect class ChannelImpl(entries: Int) {
    fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long)
    fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long)
    fun accept(file: FileImpl, userData: Long)
    fun connect(file: FileImpl, address: String, port: Int, userData: Long)
    fun close(file: FileImpl, userData: Long)
    fun submit(): Int
    fun wait(minComplete: Int = 1): List<SelectionResult>
    fun peek(): List<SelectionResult>
}

internal expect object FilesImpl {
    fun open(path: String, readOnly: Boolean = true): FileImpl
}

internal expect object ChannelsImpl {
    fun open(entries: Int = 256): ChannelImpl
    fun socket(domain: Int, type: Int, protocol: Int): FileImpl
}
