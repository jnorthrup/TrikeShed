@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.lib.Closeable
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.atomics.AtomicInt

/** An independently owned mmap region; closing the file descriptor does not unmap it.
 * All arguments use Linux bit values. Access and lifetime are serialized by its owner.
 * Registration retains this mapping until unregister or ring teardown releases it.
 */
class MemoryMapping internal constructor(
    private val access: MemoryMappingAccess,
    private val protection: Int,
) : Closeable {
    val address: Long get() = access.address
    val length: Long get() = access.length
    private val lock = Mutex()
    // Nonnegative counts borrow registrations; -1 excludes retention during close and after unmap.
    private val retained = AtomicInt(0)
    val isOpen: Boolean get() = retained.load() >= 0

    private fun checkAccess(offset: Long, length: Long, protection: Int) {
        check(isOpen) { "Memory mapping is closed" }
        require(offset >= 0 && length >= 0 && offset <= this.length && length <= this.length - offset) {
            "Memory range exceeds mapping: offset=$offset length=$length capacity=${this.length}"
        }
        check(this.protection and protection == protection) { "Memory protection denies access" }
    }
    operator fun get(offset: Long): Byte = exclusive {
        checkAccess(offset, 1, 1)
        access[offset]
    }
    operator fun set(offset: Long, value: Byte): Unit = exclusive {
        checkAccess(offset, 1, 2)
        access[offset] = value
    }
    fun read(offset: Long, dst: ByteArray, start: Int = 0, length: Int = dst.size - start): Unit = exclusive {
        checkArray(dst.size, start, length)
        checkAccess(offset, length.toLong(), 1)
        access.read(offset, dst, start, length)
    }
    fun write(offset: Long, src: ByteArray, start: Int = 0, length: Int = src.size - start): Unit = exclusive {
        checkArray(src.size, start, length)
        checkAccess(offset, length.toLong(), 2)
        access.write(offset, src, start, length)
    }
    fun sync(offset: Long = 0, length: Long = this.length, flags: Int = 4): Unit = exclusive {
        checkAccess(offset, length, 0)
        access.sync(offset, length, flags)
    }
    internal fun retain() {
        while (true) {
            val count = retained.load()
            check(count >= 0) { "Memory mapping is closed" }
            check(count < Int.MAX_VALUE) { "Memory mapping registration count overflow" }
            if (retained.compareAndSet(count, count + 1)) return
        }
    }
    internal fun release() {
        while (true) {
            val count = retained.load()
            check(count > 0) { "Memory mapping has no retained registration" }
            if (retained.compareAndSet(count, count - 1)) return
        }
    }
    override fun close(): Unit = exclusive {
        if (!isOpen) return@exclusive
        check(retained.compareAndSet(0, -1)) { "Memory mapping is retained by registered buffers" }
        try { access.close() } catch (failure: Throwable) {
            retained.store(0)
            throw failure
        }
    }
    private inline fun <T> exclusive(operation: () -> T): T {
        check(lock.tryLock()) { "Concurrent memory mapping access requires serialization by its owner" }
        try { return operation() } finally { lock.unlock() }
    }
    private fun checkArray(size: Int, start: Int, length: Int) {
        require(start >= 0 && length >= 0 && start <= size - length) { "Invalid byte array range" }
    }
}

/** Raw platform effects only; checks and lifetime belong to [MemoryMapping]. */
internal interface MemoryMappingAccess : Closeable {
    val address: Long
    val length: Long
    operator fun get(offset: Long): Byte
    operator fun set(offset: Long, value: Byte)
    fun read(offset: Long, dst: ByteArray, start: Int, length: Int)
    fun write(offset: Long, src: ByteArray, start: Int, length: Int)
    fun sync(offset: Long, length: Long, flags: Int)
}

/** fd is a userspace table identity on JVM and an OS descriptor on POSIX; -1 for anonymous memory. */
fun mapMemory(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemoryMapping {
    require(address >= 0 && length > 0 && length <= Long.MAX_VALUE - address)
    require(offset >= 0 && length <= Long.MAX_VALUE - offset)
    require(protection and 7.inv() == 0) { "Unsupported memory protection bits" }
    val access = mapMemoryAccess(address, length, protection, flags, fd, offset)
    if (access.address <= 0 || access.length != length || length > Long.MAX_VALUE - access.address) {
        access.close()
        throw UnsupportedOperationException("Mapped address cannot be represented by this memory owner")
    }
    return MemoryMapping(access, protection)
}

internal expect fun mapMemoryAccess(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemoryMappingAccess

/** Returns zero or negative Linux errno. Advice is a hint, not a residency guarantee. */
expect fun adviseMemory(address: Long, length: Long, advice: Int): Int
