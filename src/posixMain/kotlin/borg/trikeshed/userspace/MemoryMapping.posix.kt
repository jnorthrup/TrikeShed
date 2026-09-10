@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package borg.trikeshed.userspace

import kotlinx.cinterop.*
import kotlin.native.OsFamily
import kotlin.native.Platform
import platform.posix.*

private val linuxMemory: Boolean get() = Platform.osFamily == OsFamily.LINUX

private fun memoryMapFlags(flags: Int): Int {
    if (linuxMemory) return flags
    if (Platform.osFamily != OsFamily.MACOSX || flags and (1 or 2 or 0x10 or 0x20).inv() != 0)
        throw UnsupportedOperationException("Linux mmap flags are unavailable on this host: $flags")
    return (flags and 0x20.inv()) or if (flags and 0x20 != 0) MAP_ANON else 0
}

private class PosixMemoryAccess(
    override val address: Long,
    override val length: Long,
) : MemoryMappingAccess {
    override fun get(offset: Long): Byte = requireNotNull((address + offset).toCPointer<ByteVar>()).pointed.value
    override fun set(offset: Long, value: Byte) {
        requireNotNull((address + offset).toCPointer<ByteVar>()).pointed.value = value
    }
    override fun read(offset: Long, dst: ByteArray, start: Int, length: Int) {
        if (length > 0) dst.usePinned { memcpy(it.addressOf(start), (address + offset).toCPointer<ByteVar>(), length.convert()) }
    }
    override fun write(offset: Long, src: ByteArray, start: Int, length: Int) {
        if (length > 0) src.usePinned { memcpy((address + offset).toCPointer<ByteVar>(), it.addressOf(start), length.convert()) }
    }
    override fun sync(offset: Long, length: Long, flags: Int) {
        if (!linuxMemory && flags and 7.inv() != 0) throw UnsupportedOperationException("Unsupported Linux msync flags: $flags")
        val hostFlags = if (linuxMemory) flags else (flags and 4.inv()) or if (flags and 4 != 0) MS_SYNC else 0
        val result = posixCompletion(msync((address + offset).toCPointer<ByteVar>(), length.convert(), hostFlags))
        check(result == 0) { "msync failed: $result" }
    }
    override fun close() {
        val result = posixCompletion(munmap(address.toCPointer<ByteVar>(), length.convert()))
        check(result == 0) { "munmap failed: $result" }
    }
}

internal actual fun mapMemoryAccess(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemoryMappingAccess {
    val hostFlags = memoryMapFlags(flags)
    val result = mmap(address.toCPointer<ByteVar>(), length.convert(), protection, hostFlags,
        if (flags and 0x20 != 0) -1 else fd, offset)
    if (result == MAP_FAILED) throw IllegalStateException("mmap failed: ${posixCompletion(-1)}")
    val base = result?.toLong() ?: 0L
    return PosixMemoryAccess(base, length)
}

actual fun adviseMemory(address: Long, length: Long, advice: Int): Int {
    if (length < 0 || address < 0 || length > Long.MAX_VALUE - address) return -22
    if (!linuxMemory && advice !in 0..3) return -95
    return posixCompletion(madvise(address.toCPointer<ByteVar>(), length.convert(), advice))
}
