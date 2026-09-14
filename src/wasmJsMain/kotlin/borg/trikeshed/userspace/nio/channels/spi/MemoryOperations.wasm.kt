package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.MemoryMappingAccess

internal actual fun mapMemoryAccess(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemoryMappingAccess =
    throw UnsupportedOperationException("This WebAssembly runtime has no mmap binding")

internal actual fun adviseMemoryAccess(address: Long, length: Long, advice: Int): Int = -95

internal actual fun memoryPageSize(): Long = throw UnsupportedOperationException("This runtime has no mmap binding")
