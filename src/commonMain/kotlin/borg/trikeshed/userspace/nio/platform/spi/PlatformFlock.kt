package borg.trikeshed.userspace.nio.platform.spi

/**
 * flock(2) on a descriptor the uring facade opened: 0, or -errno in Linux numbering. liburing has
 * no lock opcode, so this one call stays outside the ring.
 */
expect fun platformFlock(fd: Int, operation: Int): Int
