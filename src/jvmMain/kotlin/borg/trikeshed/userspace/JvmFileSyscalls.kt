package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.AT_EMPTY_PATH
import borg.trikeshed.userspace.UringOp.Companion.AT_FDCWD
import borg.trikeshed.userspace.UringOp.Companion.AT_REMOVEDIR
import borg.trikeshed.userspace.UringOp.Companion.AT_SYMLINK_NOFOLLOW
import borg.trikeshed.userspace.UringOp.Companion.O_APPEND
import borg.trikeshed.userspace.UringOp.Companion.O_CLOEXEC
import borg.trikeshed.userspace.UringOp.Companion.O_CREAT
import borg.trikeshed.userspace.UringOp.Companion.O_DIRECTORY
import borg.trikeshed.userspace.UringOp.Companion.O_EXCL
import borg.trikeshed.userspace.UringOp.Companion.O_NOFOLLOW
import borg.trikeshed.userspace.UringOp.Companion.O_NONBLOCK
import borg.trikeshed.userspace.UringOp.Companion.O_TRUNC
import borg.trikeshed.userspace.UringOp.Companion.STATX_BASIC_STATS
import borg.trikeshed.userspace.UringOp.Companion.STATX_SIZE
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.ByteOrder
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * POSIX file effects for the JVM emulated ring, beside [JvmSocketSyscalls]: Linux O_*, AT_* and
 * struct statx in, the host's numbering at the call, -errno in Linux numbering out. Descriptors are
 * the operating system's, so flock, fstat and directory fsync act on the file the ring opened.
 */
internal object JvmFileSyscalls {
    val linux = System.getProperty("os.name").lowercase().contains("linux")
    val darwin = System.getProperty("os.name").lowercase().contains("mac")
    val posix: Boolean get() = linux || darwin
    val arm64 = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
    private val linker by lazy {
        if (!posix) TODO("JVM file actual requires a Win32 backend on this host")
        Linker.nativeLinker()
    }
    private val int = ValueLayout.JAVA_INT
    private val long = ValueLayout.JAVA_LONG
    private val short = ValueLayout.JAVA_SHORT
    private val pointer = ValueLayout.ADDRESS
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(groupElement("errno"))
    private fun function(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle = linker.downcallHandle(
        linker.defaultLookup().find(name).orElseThrow { UnsupportedOperationException("libc $name is unavailable") },
        descriptor, Linker.Option.captureCallState("errno"), *options,
    )
    private val openatCall by lazy { function("openat", FunctionDescriptor.of(int, int, pointer, int, int), Linker.Option.firstVariadicArg(3)) }
    private val preadCall by lazy { function("pread", FunctionDescriptor.of(long, int, pointer, long, long)) }
    private val pwriteCall by lazy { function("pwrite", FunctionDescriptor.of(long, int, pointer, long, long)) }
    private val readCall by lazy { function("read", FunctionDescriptor.of(long, int, pointer, long)) }
    private val writeCall by lazy { function("write", FunctionDescriptor.of(long, int, pointer, long)) }
    private val fsyncCall by lazy { function("fsync", FunctionDescriptor.of(int, int)) }
    private val fdatasyncCall by lazy { function(if (linux) "fdatasync" else "fsync", FunctionDescriptor.of(int, int)) }
    private val ftruncateCall by lazy { function("ftruncate", FunctionDescriptor.of(int, int, long)) }
    private val closeCall by lazy { function("close", FunctionDescriptor.of(int, int)) }
    private val statxCall by lazy { function("statx", FunctionDescriptor.of(int, int, pointer, int, int, pointer)) }
    private val fstatCall by lazy { function(if (arm64) "fstat" else "fstat\$INODE64", FunctionDescriptor.of(int, int, pointer)) }
    private val fstatatCall by lazy { function(if (arm64) "fstatat" else "fstatat\$INODE64", FunctionDescriptor.of(int, int, pointer, pointer, int)) }
    private val mkdiratCall by lazy { function("mkdirat", FunctionDescriptor.of(int, int, pointer, if (darwin) short else int)) }
    private val renameatCall by lazy { function("renameat", FunctionDescriptor.of(int, int, pointer, int, pointer)) }
    private val unlinkatCall by lazy { function("unlinkat", FunctionDescriptor.of(int, int, pointer, int)) }
    private val flockCall by lazy { function("flock", FunctionDescriptor.of(int, int, int)) }
    private val geteuidCall by lazy { linker.downcallHandle(linker.defaultLookup().find("geteuid").orElseThrow(), FunctionDescriptor.of(int)) }
    private val syscallCall by lazy { function("syscall", FunctionDescriptor.of(long, long, long, pointer, long), Linker.Option.firstVariadicArg(1)) }
    private val getdirentriesCall by lazy { function("__getdirentries64", FunctionDescriptor.of(long, int, pointer, long, pointer)) }

    /** Darwin errno to Linux numbering, as [JvmSocketSyscalls] maps it for sockets. */
    private fun errno(value: Int): Int = if (linux) value else when (value) {
        35 -> 11; 36 -> 115; 37 -> 114; 45 -> 95; 62 -> 40; 63 -> 36; 66 -> 39; 69 -> 122; 78 -> 38
        else -> value
    }

    private val errnoState = ThreadLocal.withInitial { Arena.ofAuto().allocate(stateLayout) }
    private fun status(state: MemorySegment, result: Long): Int = if (result < 0) -errno(state.get(int, errnoOffset)) else result.toInt()
    private fun status(state: MemorySegment, result: Int): Int = status(state, result.toLong())

    /** Linux asm-generic O_* flags to the host's numbering; null for a flag the facade does not carry. */
    fun openFlags(flags: Int): Int? {
        val known = 3 or O_CREAT or O_EXCL or O_TRUNC or O_APPEND or O_NONBLOCK or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC
        if (flags and known.inv() != 0 || flags and 3 == 3) return null
        fun bit(flag: Int, host: Int) = if (flags and flag != 0) host else 0
        return if (linux) (flags and 3) or bit(O_CREAT, 0x40) or bit(O_EXCL, 0x80) or bit(O_TRUNC, 0x200) or
            bit(O_APPEND, 0x400) or bit(O_NONBLOCK, 0x800) or bit(O_CLOEXEC, 0x80000) or
            bit(O_DIRECTORY, if (arm64) 0x4000 else 0x10000) or bit(O_NOFOLLOW, if (arm64) 0x8000 else 0x20000)
        else (flags and 3) or bit(O_CREAT, 0x200) or bit(O_EXCL, 0x800) or bit(O_TRUNC, 0x400) or bit(O_APPEND, 0x8) or
            bit(O_NONBLOCK, 0x4) or bit(O_CLOEXEC, 0x1000000) or bit(O_DIRECTORY, 0x100000) or bit(O_NOFOLLOW, 0x100)
    }

    private fun dirfd(fd: Int): Int = if (fd == AT_FDCWD && darwin) -2 else fd

    private inline fun <T> path(path: String, action: (MemorySegment) -> T): T = Arena.ofConfined().use { action(it.allocateFrom(path)) }

    fun openat(dfd: Int, path: String, flags: Int, mode: Int): Int {
        val host = openFlags(flags) ?: return -22
        return path(path) { name -> val state = errnoState.get(); status(state, openatCall.invokeExact(state, dirfd(dfd), name, host, mode) as Int) }
    }

    /** pread/pwrite through a native copy of the heap window; offset -1 uses the descriptor position. */
    fun transfer(fd: Int, bytes: ByteArray, start: Int, length: Int, offset: Long, read: Boolean): Int = Arena.ofConfined().use { arena ->
        val native = arena.allocate(maxOf(1, length).toLong())
        if (!read) MemorySegment.copy(MemorySegment.ofArray(bytes), start.toLong(), native, 0, length.toLong())
        val count = transfer(fd, native, length, offset, read)
        if (read && count > 0) MemorySegment.copy(native, 0, MemorySegment.ofArray(bytes), start.toLong(), count.toLong())
        count
    }

    fun transfer(fd: Int, native: MemorySegment, length: Int, offset: Long, read: Boolean): Int {
        val state = errnoState.get()
        val result = when {
            offset == -1L && read -> readCall.invokeExact(state, fd, native, length.toLong()) as Long
            offset == -1L -> writeCall.invokeExact(state, fd, native, length.toLong()) as Long
            read -> preadCall.invokeExact(state, fd, native, length.toLong(), offset) as Long
            else -> pwriteCall.invokeExact(state, fd, native, length.toLong(), offset) as Long
        }
        return status(state, result)
    }

    fun fsync(fd: Int, datasync: Boolean): Int {
        val state = errnoState.get()
        return status(state, (if (datasync) fdatasyncCall else fsyncCall).invokeExact(state, fd) as Int)
    }

    fun ftruncate(fd: Int, length: Long): Int { val state = errnoState.get(); return status(state, ftruncateCall.invokeExact(state, fd, length) as Int) }

    fun close(fd: Int): Int { val state = errnoState.get(); return status(state, closeCall.invokeExact(state, fd) as Int) }

    fun flock(fd: Int, operation: Int): Int { val state = errnoState.get(); return status(state, flockCall.invokeExact(state, fd, operation) as Int) }

    fun geteuid(): UInt = (geteuidCall.invokeExact() as Int).toUInt()

    fun mkdirat(dfd: Int, path: String, mode: Int): Int = path(path) { name ->
        val state = errnoState.get()
        status(state, if (darwin) mkdiratCall.invokeExact(state, dirfd(dfd), name, mode.toShort()) as Int
            else mkdiratCall.invokeExact(state, dirfd(dfd), name, mode) as Int)
    }

    fun renameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String): Int = path(oldPath) { from ->
        path(newPath) { to -> val state = errnoState.get(); status(state, renameatCall.invokeExact(state, dirfd(oldDfd), from, dirfd(newDfd), to) as Int) }
    }

    fun unlinkat(dfd: Int, path: String, flags: Int): Int {
        if (flags and AT_REMOVEDIR.inv() != 0) return -22
        val host = if (flags and AT_REMOVEDIR != 0) (if (darwin) 0x80 else 0x200) else 0
        return path(path) { name -> val state = errnoState.get(); status(state, unlinkatCall.invokeExact(state, dirfd(dfd), name, host) as Int) }
    }

    /**
     * statx(dfd, path, flags, mask) into [out] at [at]: Linux writes the kernel struct itself; Darwin
     * fills it from fstat or fstatat in host order, its dev_t split as major(8)/minor(24) bits.
     */
    fun statx(dfd: Int, path: String, flags: Int, mask: Int, out: ByteArray, at: Int): Int = Arena.ofConfined().use { arena ->
        if (flags and (AT_SYMLINK_NOFOLLOW or AT_EMPTY_PATH or 0x800).inv() != 0) return -22
        val state = errnoState.get()
        if (linux) {
            val buffer = arena.allocate(STATX_SIZE.toLong(), 8)
            val result = status(state, statxCall.invokeExact(state, dirfd(dfd), arena.allocateFrom(path), flags, mask, buffer) as Int)
            if (result == 0) MemorySegment.copy(buffer, 0, MemorySegment.ofArray(out), at.toLong(), STATX_SIZE.toLong())
            return result
        }
        val stat = arena.allocate(144, 8)
        val result = if (path.isEmpty()) {
            if (flags and AT_EMPTY_PATH == 0) return -2
            status(state, fstatCall.invokeExact(state, dfd, stat) as Int)
        } else status(state, fstatatCall.invokeExact(state, dirfd(dfd), arena.allocateFrom(path), stat,
            if (flags and AT_SYMLINK_NOFOLLOW != 0) 0x20 else 0) as Int)
        if (result != 0) return result
        fun i32(offset: Long) = stat.get(int, offset)
        fun i64(offset: Long) = stat.get(long, offset)
        val dev = i32(0)
        val rdev = i32(24)
        val statx = ByteBuffer.wrap(out, at, STATX_SIZE).order(ByteOrder.nativeOrder())
        statx.putInt(0x00, STATX_BASIC_STATS or 0x800)
        statx.putInt(0x04, i32(112))
        statx.putInt(0x10, stat.get(short, 6).toInt() and 0xffff)
        statx.putInt(0x14, i32(16))
        statx.putInt(0x18, i32(20))
        statx.putShort(0x1c, stat.get(short, 4))
        statx.putLong(0x20, i64(8))
        statx.putLong(0x28, i64(96))
        statx.putLong(0x30, i64(104))
        for ((field, source) in listOf(0x40 to 32L, 0x50 to 80L, 0x60 to 64L, 0x70 to 48L)) {
            statx.putLong(field, i64(source))
            statx.putInt(field + 8, i64(source + 8).toInt())
        }
        statx.putInt(0x80, (rdev ushr 24) and 0xff)
        statx.putInt(0x84, rdev and 0xffffff)
        statx.putInt(0x88, (dev ushr 24) and 0xff)
        statx.putInt(0x8c, dev and 0xffffff)
        0
    }

    /** The size fstat reports for [fd], or -errno. */
    fun size(fd: Int): Long {
        val out = ByteArray(STATX_SIZE)
        val result = statx(fd, "", AT_EMPTY_PATH, STATX_BASIC_STATS, out, 0)
        return if (result < 0) result.toLong() else ByteBuffer.wrap(out).order(ByteOrder.nativeOrder()).getLong(0x28)
    }

    /**
     * getdents64 into [out]: Linux's syscall itself; Darwin's __getdirentries64 records rewritten as
     * linux_dirent64 (d_ino, d_off, d_reclen, d_type, d_name), each no longer than its source record.
     */
    fun getdents(fd: Int, out: ByteArray, at: Int, length: Int): Int = Arena.ofConfined().use { arena ->
        val native = arena.allocate(maxOf(1, length).toLong(), 8)
        val state = errnoState.get()
        if (linux) {
            val result = status(state, syscallCall.invokeExact(state, if (arm64) 61L else 217L, fd.toLong(), native, length.toLong()) as Long)
            if (result > 0) MemorySegment.copy(native, 0, MemorySegment.ofArray(out), at.toLong(), result.toLong())
            return result
        }
        val base = arena.allocate(long)
        val result = status(state, getdirentriesCall.invokeExact(state, fd, native, length.toLong(), base) as Long)
        if (result <= 0) return result
        val records = ByteBuffer.wrap(out, at, length).order(ByteOrder.nativeOrder())
        var read = 0L
        var written = 0
        while (read < result) {
            val reclen = native.get(short, read + 16).toInt() and 0xffff
            val namlen = native.get(short, read + 18).toInt() and 0xffff
            val size = (19 + namlen + 1 + 7) and 7.inv()
            records.putLong(written, native.get(long, read))
            records.putLong(written + 8, native.get(long, read + 8))
            records.putShort(written + 16, size.toShort())
            records.put(written + 18, native.get(ValueLayout.JAVA_BYTE, read + 20))
            MemorySegment.copy(native, read + 21, MemorySegment.ofArray(out), (at + written + 19).toLong(), namlen.toLong())
            for (pad in written + 19 + namlen until written + size) records.put(pad, 0.toByte())
            written += size
            read += reclen
        }
        written
    }
}
