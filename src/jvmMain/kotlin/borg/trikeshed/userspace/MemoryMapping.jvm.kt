package borg.trikeshed.userspace

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.channels.FileChannel

/** libc calls keep setup and VM advice independent of descriptor and ring lifetimes. */
internal object JvmMemorySyscalls {
    val linux = System.getProperty("os.name").lowercase().contains("linux")
    private val darwin = System.getProperty("os.name").lowercase().contains("mac")
    private val linker by lazy {
        if (!linux && !darwin) throw UnsupportedOperationException("Memory mapping requires Linux or Darwin libc")
        Linker.nativeLinker()
    }
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(groupElement("errno"))
    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        linker.defaultLookup().find(name).orElseThrow { UnsupportedOperationException("libc $name is unavailable") },
        descriptor, Linker.Option.captureCallState("errno"),
    )
    private val mmap by lazy { function("mmap", FunctionDescriptor.of(ValueLayout.ADDRESS,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
        ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)) }
    private val munmap by lazy { function("munmap", FunctionDescriptor.of(ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)) }
    private val msync by lazy { function("msync", FunctionDescriptor.of(ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)) }
    private val madvise by lazy { function("madvise", FunctionDescriptor.of(ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)) }
    val pageSize: Long by lazy {
        val call = function("getpagesize", FunctionDescriptor.of(ValueLayout.JAVA_INT))
        status(call).toLong().also { check(it > 0) }
    }

    private fun errno(state: MemorySegment): Int {
        val value = state.get(ValueLayout.JAVA_INT, errnoOffset)
        return if (linux) value else when (value) {
            35 -> 11; 45 -> 95; 78 -> 38; 84 -> 75
            else -> value
        }
    }
    private fun status(handle: MethodHandle, vararg args: Any): Int = Arena.ofConfined().use { arena ->
        val state = arena.allocate(stateLayout)
        val result = handle.invokeWithArguments(listOf(state) + args) as Int
        if (result < 0) -errno(state) else result
    }
    private fun mapFlags(flags: Int): Int {
        if (linux) return flags
        if (!darwin || flags and (1 or 2 or 0x10 or 0x20).inv() != 0)
            throw UnsupportedOperationException("Linux mmap flags are unavailable on this host: $flags")
        return (flags and 0x20.inv()) or if (flags and 0x20 != 0) 0x1000 else 0
    }
    fun map(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemorySegment {
        val hostFlags = mapFlags(flags)
        return Arena.ofConfined().use { arena ->
            val state = arena.allocate(stateLayout)
            val result = mmap.invokeWithArguments(state, MemorySegment.ofAddress(address), length,
                protection, hostFlags, fd, offset) as MemorySegment
            if (result.address() == -1L) throw IllegalStateException("mmap failed: ${-errno(state)}")
            result.reinterpret(length)
        }
    }
    fun unmap(address: Long, length: Long): Int = status(munmap, MemorySegment.ofAddress(address), length)
    fun sync(address: Long, length: Long, flags: Int): Int {
        if (!linux && flags and 7.inv() != 0) return -95
        val hostFlags = if (linux) flags else (flags and 4.inv()) or if (flags and 4 != 0) 0x10 else 0
        return status(msync, MemorySegment.ofAddress(address), length, hostFlags)
    }
    fun advise(address: Long, length: Long, advice: Int): Int {
        if (!linux && advice !in 0..3) return -95
        return status(madvise, MemorySegment.ofAddress(address), length, advice)
    }
}

private class JvmMemoryAccess(
    private val segment: MemorySegment,
    private val arena: Arena? = null,
) : MemoryMappingAccess {
    override val address: Long get() = segment.address()
    override val length: Long get() = segment.byteSize()
    override fun get(offset: Long): Byte = segment.get(ValueLayout.JAVA_BYTE, offset)
    override fun set(offset: Long, value: Byte) = segment.set(ValueLayout.JAVA_BYTE, offset, value)
    override fun read(offset: Long, dst: ByteArray, start: Int, length: Int) =
        MemorySegment.copy(segment, offset, MemorySegment.ofArray(dst), start.toLong(), length.toLong())
    override fun write(offset: Long, src: ByteArray, start: Int, length: Int) =
        MemorySegment.copy(MemorySegment.ofArray(src), start.toLong(), segment, offset, length.toLong())
    override fun sync(offset: Long, length: Long, flags: Int) {
        val result = JvmMemorySyscalls.sync(address + offset, length, flags)
        check(result == 0) { "msync failed: $result" }
    }
    override fun close() {
        if (arena != null) arena.close()
        else {
            val result = JvmMemorySyscalls.unmap(address, length)
            check(result == 0) { "munmap failed: $result" }
        }
    }
}

internal actual fun mapMemoryAccess(address: Long, length: Long, protection: Int, flags: Int, fd: Int, offset: Long): MemoryMappingAccess {
    val anonymous = flags and 0x20 != 0
    if (anonymous) return JvmMemoryAccess(JvmMemorySyscalls.map(address, length, protection, flags, -1, offset))
    val descriptor = JvmFileTable.descriptor(fd) ?: throw IllegalArgumentException("Unknown file descriptor: $fd")
    if (descriptor is JvmNativeDescriptor) return synchronized(descriptor) {
        check(descriptor.isOpen()) { "File descriptor is closed" }
        JvmMemoryAccess(JvmMemorySyscalls.map(address, length, protection, flags, descriptor.fd, offset))
    }
    if (descriptor !is JvmChannelDescriptor) throw UnsupportedOperationException("Descriptor cannot be memory mapped")
    if (address != 0L || flags !in 1..2 || protection !in intArrayOf(1, 3))
        throw UnsupportedOperationException("JDK file mappings support READ or READ|WRITE with MAP_SHARED or MAP_PRIVATE")
    require(offset % JvmMemorySyscalls.pageSize == 0L) { "mmap offset must be page aligned" }
    if (offset > descriptor.channel.size() || length > descriptor.channel.size() - offset)
        throw UnsupportedOperationException("JDK mapping beyond EOF would resize the file; use a native descriptor")
    val mode = when {
        flags == 2 -> FileChannel.MapMode.PRIVATE
        protection == 1 -> FileChannel.MapMode.READ_ONLY
        else -> FileChannel.MapMode.READ_WRITE
    }
    val arena = Arena.ofShared()
    return try { JvmMemoryAccess(descriptor.channel.map(mode, offset, length, arena), arena) }
    catch (failure: Throwable) { arena.close(); throw failure }
}

actual fun adviseMemory(address: Long, length: Long, advice: Int): Int {
    if (length < 0 || address < 0 || length > Long.MAX_VALUE - address) return -22
    return JvmMemorySyscalls.advise(address, length, advice)
}
