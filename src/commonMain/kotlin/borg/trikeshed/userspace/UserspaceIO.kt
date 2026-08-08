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

expect fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend