package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

data class SelectionResult(val res: Int, val userData: Long)

/**
 * Unified io_uring-style submission queue.
 *
 * Two APIs coexist:
 * 1. **Typed** — [read], [write], [accept], [connect], [close], [sync], [truncate], [map] + [submit]/[wait]/[peek]
 * 2. **Unified** — [enqueue] any [UringSubmission], then [submit]/[wait]/[peek]
 *
 * The typed API is sugar that creates [UringSubmission] internally.
 * New code should use the unified path exclusively.
 */
class Channel(
    private val facade: FunctionalUringFacade,
) {
    fun read(file: File, buffer: ByteBuffer, offset: Long, userData: Long) =
        facade.read(file.impl, buffer, offset, userData)

    fun write(file: File, buffer: ByteBuffer, offset: Long, userData: Long) =
        facade.write(file.impl, buffer, offset, userData)

    fun accept(file: File, userData: Long) =
        facade.accept(file.impl, userData)

    fun connect(file: File, address: String, port: Int, userData: Long) =
        facade.connect(file.impl, address, port, userData)

    fun close(file: File, userData: Long) =
        facade.close(file.impl, userData)

    fun sync(file: File, userData: Long, metaData: Boolean) =
        facade.sync(file.impl, userData, metaData)

    fun truncate(file: File, size: Long, userData: Long) =
        facade.truncate(file.impl, size, userData)

    fun map(file: File, mode: String, position: Long, size: Long, userData: Long) =
        facade.map(file.impl, mode, position, size, userData)

    fun submit(): Int = facade.submit()

    fun wait(minComplete: Int = 1): List<SelectionResult> = facade.wait(minComplete)

    fun peek(): List<SelectionResult> = facade.peek()
}

class File internal constructor(internal val impl: FileImpl) {
    val id: Int get() = impl.id
    fun isOpen(): Boolean = impl.isOpen()
    fun close() = impl.close()
    fun size(): Long = impl.size()
}

expect class FileImpl(id: Int) {
    val id: Int
    fun isOpen(): Boolean
    fun close()
    fun size(): Long
}

internal expect object FilesImpl {
    fun open(path: String, readOnly: Boolean = true): FileImpl
}

internal expect object ChannelsImpl {
    fun socket(domain: Int, type: Int, protocol: Int): FileImpl
}

internal expect fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend