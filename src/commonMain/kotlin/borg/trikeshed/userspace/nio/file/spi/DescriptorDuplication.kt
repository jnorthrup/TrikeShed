package borg.trikeshed.userspace.nio.file.spi

/**
 * Optional capability for a separately owned, close-on-exec file descriptor.
 * The caller owns the source descriptor and serializes this call with any close
 * submitted outside that owner. A path reopen does not satisfy this capability.
 * On POSIX, the caller must also account for process-associated file locks that
 * may be released when the duplicate closes.
 */
interface DescriptorDuplication {
    fun duplicateDescriptor(id: Int): Int
}
