package borg.trikeshed.userspace

internal actual fun mapMemoryAccess(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemoryMappingAccess =
    throw UnsupportedOperationException("This WebAssembly runtime has no mmap binding")

actual fun adviseMemory(address: Long, length: Long, advice: Int): Int = -95
