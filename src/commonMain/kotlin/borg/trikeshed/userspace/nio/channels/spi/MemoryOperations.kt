package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.MemoryMappingAccess

/** VM effects below the common memory owner. These are not io_uring opcodes. */
internal expect fun mapMemoryAccess(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemoryMappingAccess

internal expect fun memoryPageSize(): Long

internal expect fun adviseMemoryAccess(address: Long, length: Long, advice: Int): Int
