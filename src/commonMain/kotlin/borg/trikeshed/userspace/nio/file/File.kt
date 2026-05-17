package borg.trikeshed.userspace.nio.file

import borg.trikeshed.userspace.FileImpl

/**
 * Open file handle — backed by [FileImpl] (expect/actual).
 *
 * Used by [borg.trikeshed.userspace.nio.channel.Channel] operations.
 * For directory-level operations use [Files].
 */
class File internal constructor(internal val impl: FileImpl) {
    companion object {
        internal fun fromFd(fd: Int): File = File(FileImpl(fd))
    }

    val id: Int get() = impl.id
    fun isOpen(): Boolean = impl.isOpen()
    fun close() = impl.close()
    fun size(): Long = impl.size()
}