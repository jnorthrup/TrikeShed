package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.userspace.JvmFileSyscalls
import borg.trikeshed.userspace.JvmFileTable
import borg.trikeshed.userspace.JvmNativeDescriptor

/** The ring's descriptor table entry names the operating-system descriptor; a JDK channel has none to lock. */
actual fun platformFlock(fd: Int, operation: Int): Int {
    val descriptor = JvmFileTable.descriptor(fd) ?: return -9
    if (descriptor !is JvmNativeDescriptor) return -95
    return synchronized(descriptor) { if (!descriptor.isOpen()) -9 else JvmFileSyscalls.flock(descriptor.fd, operation) }
}
