@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.MemoryMappingAccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.GetSystemInfo
import platform.windows.SYSTEM_INFO

/** Linux mmap flags and descriptors do not map onto CreateFileMapping/MapViewOfFile; no binding yet. */
internal actual fun mapMemoryAccess(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemoryMappingAccess =
    throw UnsupportedOperationException("mmap has no Windows binding yet (CreateFileMapping/MapViewOfFile)")

internal actual fun adviseMemoryAccess(address: Long, length: Long, advice: Int): Int = -95

internal actual fun memoryPageSize(): Long = memScoped {
    val info = alloc<SYSTEM_INFO>()
    GetSystemInfo(info.ptr)
    info.dwPageSize.toLong()
}
