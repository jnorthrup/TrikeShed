@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.userspace

import borg.trikeshed.lib.Closeable
import borg.trikeshed.userspace.nio.channels.spi.adviseMemoryAccess
import borg.trikeshed.userspace.nio.channels.spi.mapMemoryAccess
import borg.trikeshed.userspace.nio.channels.spi.memoryPageSize
import kotlinx.coroutines.sync.Mutex
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.jvm.JvmOverloads
import kotlin.time.TimeSource

/** An independently owned mmap region; closing the file descriptor does not unmap it.
 * All arguments use Linux bit values. Access and lifetime are serialized by its owner.
 * Registration retains this mapping until unregister or ring teardown releases it.
 */
class MemoryMapping internal constructor(
    private val access: MemoryMappingAccess,
    private val protection: Int,
    private val observation: MemoryObservation? = null,
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
    operator fun get(offset: Long): Byte = observed(MemoryOperation.READ, offset, 1) {
        exclusive { checkAccess(offset, 1, 1); access[offset] }
    }
    operator fun set(offset: Long, value: Byte): Unit = observed(MemoryOperation.WRITE, offset, 1) {
        exclusive { checkAccess(offset, 1, 2); access[offset] = value }
    }
    fun read(offset: Long, dst: ByteArray, start: Int = 0, length: Int = dst.size - start): Unit =
        observed(MemoryOperation.READ, offset, length.toLong()) {
            exclusive {
                checkArray(dst.size, start, length)
                checkAccess(offset, length.toLong(), 1)
                access.read(offset, dst, start, length)
            }
        }
    fun write(offset: Long, src: ByteArray, start: Int = 0, length: Int = src.size - start): Unit =
        observed(MemoryOperation.WRITE, offset, length.toLong()) {
            exclusive {
                checkArray(src.size, start, length)
                checkAccess(offset, length.toLong(), 2)
                access.write(offset, src, start, length)
            }
        }
    fun sync(offset: Long = 0, length: Long = this.length, flags: Int = 4): Unit =
        observed(MemoryOperation.SYNC, offset, length, flags) {
            exclusive { checkAccess(offset, length, 0); access.sync(offset, length, flags) }
        }
    internal fun retain(): Unit = observed(MemoryOperation.RETAIN) {
        while (true) {
            val count = retained.load()
            check(count >= 0) { "Memory mapping is closed" }
            check(count < Int.MAX_VALUE) { "Memory mapping registration count overflow" }
            if (retained.compareAndSet(count, count + 1)) break
        }
    }
    internal fun release(): Unit = observed(MemoryOperation.RELEASE) {
        while (true) {
            val count = retained.load()
            check(count > 0) { "Memory mapping has no retained registration" }
            if (retained.compareAndSet(count, count - 1)) break
        }
    }
    override fun close(): Unit = exclusive {
        if (!isOpen) return@exclusive
        observed(MemoryOperation.UNMAP) {
            check(retained.compareAndSet(0, -1)) { "Memory mapping is retained by registered buffers" }
            try { access.close() } catch (failure: Throwable) {
                retained.store(0)
                throw failure
            }
        }
    }
    private inline fun <T> observed(
        operation: MemoryOperation, offset: Long = 0, length: Long = this.length, flags: Int = 0,
        action: () -> T,
    ): T = observeMemory(observation, { access }, operation, offset, length, flags, { retained.load() }, action)

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
    val backingAddress: Long get() = address
    val backingLength: Long get() = length
    operator fun get(offset: Long): Byte
    operator fun set(offset: Long, value: Byte)
    fun read(offset: Long, dst: ByteArray, start: Int, length: Int)
    fun write(offset: Long, src: ByteArray, start: Int, length: Int)
    fun sync(offset: Long, length: Long, flags: Int)
}

/** fd is a userspace table identity on JVM and an OS descriptor on POSIX; -1 for anonymous memory.
 * This raw operation preserves mmap's page-aligned offset and msync address requirements.
 */
@JvmOverloads
fun mapMemory(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long,
              trace: UringTrace? = null): MemoryMapping =
    createMapping(length, protection, flags, fd, offset, trace) {
        checkMappingArguments(address, length, protection, offset)
        mapMemoryAccess(address, length, protection, flags, fd, offset)
    }

/** A logical file range over a page-aligned backing mapping. Closing the view unmaps the entire
 * backing region; sync rounds its starting address down within that region. The requested extent
 * must already exist and remain untruncated for the mapping lifetime. This does not resize a file.
 */
@JvmOverloads
fun mapFileMemory(length: Long, protection: Int, flags: Int, fd: Int, offset: Long,
                  trace: UringTrace? = null): MemoryMapping =
    createMapping(length, protection, flags, fd, offset, trace) {
        checkMappingArguments(0, length, protection, offset)
        require(fd >= 0 && flags and (0x10 or 0x20) == 0) { "File views require a file descriptor and a nonfixed file mapping" }
        val page = memoryPageSize().also { check(it > 0) }
        val prefix = offset % page
        require(length <= Long.MAX_VALUE - prefix) { "Aligned mapping length overflow" }
        val backing = mapMemoryAccess(0, length + prefix, protection, flags, fd, offset - prefix)
        FileMemoryAccess(backing, prefix, length, page)
    }

private fun checkMappingArguments(address: Long, length: Long, protection: Int, offset: Long) {
    require(address >= 0 && length > 0 && length <= Long.MAX_VALUE - address)
    require(offset >= 0 && length <= Long.MAX_VALUE - offset)
    require(protection and 7.inv() == 0) { "Unsupported memory protection bits" }
}

private inline fun createMapping(length: Long, protection: Int, flags: Int, fd: Int, offset: Long,
                                 trace: UringTrace?, create: () -> MemoryMappingAccess): MemoryMapping {
    val observation = trace?.let { MemoryObservation(it, fd, offset, protection, flags) }
    var access: MemoryMappingAccess? = null
    return observeMemory(observation, { access }, MemoryOperation.MAP, 0, length, flags, { 0 }) {
        val mapped = create()
        access = mapped
        if (mapped.address <= 0 || mapped.length != length || length > Long.MAX_VALUE - mapped.address) {
            mapped.close()
            throw UnsupportedOperationException("Mapped address cannot be represented by this memory owner")
        }
        MemoryMapping(mapped, protection, observation)
    }
}

private class FileMemoryAccess(
    private val backing: MemoryMappingAccess,
    private val prefix: Long,
    override val length: Long,
    private val page: Long,
) : MemoryMappingAccess {
    override val address: Long get() = backing.address + prefix
    override val backingAddress: Long get() = backing.address
    override val backingLength: Long get() = backing.length
    override fun get(offset: Long): Byte = backing[prefix + offset]
    override fun set(offset: Long, value: Byte) { backing[prefix + offset] = value }
    override fun read(offset: Long, dst: ByteArray, start: Int, length: Int) = backing.read(prefix + offset, dst, start, length)
    override fun write(offset: Long, src: ByteArray, start: Int, length: Int) = backing.write(prefix + offset, src, start, length)
    override fun sync(offset: Long, length: Long, flags: Int) {
        val start = prefix + offset
        val aligned = start - start % page
        backing.sync(aligned, start - aligned + length, flags)
    }
    override fun close() = backing.close()
}

/** Returns zero or negative Linux errno. Advice is a hint, not a residency guarantee. */
fun adviseMemory(address: Long, length: Long, advice: Int): Int {
    if (length < 0 || address < 0 || length > Long.MAX_VALUE - address) return -22
    return adviseMemoryAccess(address, length, advice)
}

private val mappingIdentity = AtomicLong(1)

internal class MemoryObservation(
    private val trace: UringTrace,
    private val fd: Int,
    private val fileOffset: Long,
    private val protection: Int,
    private val mapFlags: Int,
) {
    private val id = mappingIdentity.fetchAndAdd(1)
    fun emit(access: MemoryMappingAccess?, operation: MemoryOperation, offset: Long, length: Long,
             flags: Int, elapsedNanos: Long, retained: Int, failure: Throwable?) {
        // A diagnostic observer must not leak a successful map, retention, or unmap by throwing.
        runCatching {
            trace.memory(MemoryTraceEvent(id, operation, access?.address ?: 0, length, offset,
                access?.backingAddress ?: 0, access?.backingLength ?: 0, fd, fileOffset, protection,
                if (operation == MemoryOperation.MAP) mapFlags else flags, elapsedNanos, retained,
                failure?.toString()?.take(512)))
        }
    }
}

private inline fun <T> observeMemory(
    observation: MemoryObservation?, access: () -> MemoryMappingAccess?, operation: MemoryOperation,
    offset: Long, length: Long, flags: Int, retained: () -> Int, action: () -> T,
): T {
    if (observation == null) return action()
    val start = TimeSource.Monotonic.markNow()
    var failure: Throwable? = null
    try { return action() } catch (caught: Throwable) { failure = caught; throw caught }
    finally { observation.emit(access(), operation, offset, length, flags, start.elapsedNow().inWholeNanoseconds, retained(), failure) }
}
