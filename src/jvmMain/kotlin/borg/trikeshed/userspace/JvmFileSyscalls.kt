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
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

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
    private val int = ValueLayout.JAVA_INT
    private val long = ValueLayout.JAVA_LONG
    private val short = ValueLayout.JAVA_SHORT
    private val stateLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = stateLayout.byteOffset(groupElement("errno"))
    private fun symbol(name: String): MemorySegment {
        if (!posix) TODO("JVM file actual requires a Win32 backend on this host")
        return libcSymbol(name)
    }

    // One holder per function, resolved when its class first initializes: the address is a static final the JIT folds
    // into each call site, and LibcDowncall's build-time handle carries the call. A `by lazy` address would read through
    // SynchronizedLazyImpl.getValue, whose profile every lazy in the process shares.
    private object Openat { @JvmField val at = symbol("openat") }
    private object Pread { @JvmField val at = symbol("pread") }
    private object Pwrite { @JvmField val at = symbol("pwrite") }
    private object Read { @JvmField val at = symbol("read") }
    private object Write { @JvmField val at = symbol("write") }
    private object Fsync { @JvmField val at = symbol("fsync") }
    private object Fdatasync { @JvmField val at = symbol(if (linux) "fdatasync" else "fsync") }
    private object Ftruncate { @JvmField val at = symbol("ftruncate") }
    private object Close { @JvmField val at = symbol("close") }
    private object Statx { @JvmField val at = symbol("statx") }
    private object Fstat { @JvmField val at = symbol(if (arm64) "fstat" else "fstat\$INODE64") }
    private object Fstatat { @JvmField val at = symbol(if (arm64) "fstatat" else "fstatat\$INODE64") }
    private object Mkdirat { @JvmField val at = symbol("mkdirat") }
    private object Renameat { @JvmField val at = symbol("renameat") }
    private object Unlinkat { @JvmField val at = symbol("unlinkat") }
    private object Flock { @JvmField val at = symbol("flock") }
    private object Geteuid { @JvmField val at = symbol("geteuid") }
    private object Syscall { @JvmField val at = symbol("syscall") }
    private object Getdirentries { @JvmField val at = symbol("__getdirentries64") }

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
        return path(path) { name -> val state = errnoState.get(); status(state, LibcDowncall.OPENAT.invokeExact(Openat.at, state, dirfd(dfd), name, host, mode) as Int) }
    }

    /**
     * The calling thread's native window for heap transfers, grown by doubling and kept for its next transfer, as the
     * JDK channel keeps its temporary direct buffer; a transfer past [WINDOW_CAP] takes a window of its own.
     */
    val windows = ThreadLocal<MemorySegment>()
    const val WINDOW_CAP = 1 shl 20

    fun window(length: Int): MemorySegment {
        windows.get()?.let { if (it.byteSize() >= length) return it }
        var size = 4096L
        while (size < length) size = size shl 1
        return Arena.ofAuto().allocate(size, 8).also { windows.set(it) }
    }

    // malloc/free without errno capture: native memory a transfer overwrites, which Arena.allocate would clear first.
    private object Malloc { @JvmField val at = symbol("malloc") }
    private object Free { @JvmField val at = symbol("free") }

    /**
     * [length] bytes of native memory for one transfer, to [action]: the calling thread's [window], or past [WINDOW_CAP]
     * a malloc'd block freed after it. Neither is cleared. Arena.allocate writes zeros over every byte it hands out, and a
     * transfer overwrites them: a write copies its bytes in first, a read is copied out only up to what it read, so no
     * earlier transfer's bytes leave this memory.
     */
    inline fun <T> native(length: Int, action: (MemorySegment) -> T): T {
        if (length <= WINDOW_CAP) return action(window(length))
        val block = malloc(length.toLong())
        try {
            return action(block)
        } finally {
            free(block)
        }
    }

    fun malloc(length: Long): MemorySegment {
        val address = LibcDowncall.MALLOC.invokeExact(Malloc.at, length) as MemorySegment
        if (address.address() == 0L) throw OutOfMemoryError("malloc($length)")
        return address.reinterpret(length)
    }

    fun free(block: MemorySegment) {
        LibcDowncall.FREE.invoke(Free.at, block)
    }

    /** pread/pwrite through a native copy of the heap window; offset -1 uses the descriptor position. */
    fun transfer(fd: Int, bytes: ByteArray, start: Int, length: Int, offset: Long, read: Boolean): Int =
        native(length) { transfer(fd, it, bytes, start, length, offset, read) }

    fun transfer(fd: Int, native: MemorySegment, bytes: ByteArray, start: Int, length: Int, offset: Long, read: Boolean): Int {
        if (!read) MemorySegment.copy(MemorySegment.ofArray(bytes), start.toLong(), native, 0, length.toLong())
        val count = transfer(fd, native, length, offset, read)
        if (read && count > 0) MemorySegment.copy(native, 0, MemorySegment.ofArray(bytes), start.toLong(), count.toLong())
        return count
    }

    fun transfer(fd: Int, native: MemorySegment, length: Int, offset: Long, read: Boolean): Int {
        val state = errnoState.get()
        val result = when {
            offset == -1L && read -> LibcDowncall.READ.invokeExact(Read.at, state, fd, native, length.toLong()) as Long
            offset == -1L -> LibcDowncall.READ.invokeExact(Write.at, state, fd, native, length.toLong()) as Long
            read -> LibcDowncall.PREAD.invokeExact(Pread.at, state, fd, native, length.toLong(), offset) as Long
            else -> LibcDowncall.PREAD.invokeExact(Pwrite.at, state, fd, native, length.toLong(), offset) as Long
        }
        return status(state, result)
    }

    fun fsync(fd: Int, datasync: Boolean): Int {
        val state = errnoState.get()
        return status(state, LibcDowncall.FD.invokeExact(if (datasync) Fdatasync.at else Fsync.at, state, fd) as Int)
    }

    fun ftruncate(fd: Int, length: Long): Int { val state = errnoState.get(); return status(state, LibcDowncall.FTRUNCATE.invokeExact(Ftruncate.at, state, fd, length) as Int) }

    fun close(fd: Int): Int { val state = errnoState.get(); return status(state, LibcDowncall.FD.invokeExact(Close.at, state, fd) as Int) }

    fun flock(fd: Int, operation: Int): Int { val state = errnoState.get(); return status(state, LibcDowncall.FD_INT.invokeExact(Flock.at, state, fd, operation) as Int) }

    fun geteuid(): UInt = (LibcDowncall.GETEUID.invokeExact(Geteuid.at) as Int).toUInt()

    fun mkdirat(dfd: Int, path: String, mode: Int): Int = path(path) { name ->
        val state = errnoState.get()
        status(state, if (darwin) LibcDowncall.MKDIRAT.invokeExact(Mkdirat.at, state, dirfd(dfd), name, mode.toShort()) as Int
            else LibcDowncall.MKDIRAT.invokeExact(Mkdirat.at, state, dirfd(dfd), name, mode) as Int)
    }

    fun renameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String): Int = path(oldPath) { from ->
        path(newPath) { to -> val state = errnoState.get(); status(state, LibcDowncall.RENAMEAT.invokeExact(Renameat.at, state, dirfd(oldDfd), from, dirfd(newDfd), to) as Int) }
    }

    fun unlinkat(dfd: Int, path: String, flags: Int): Int {
        if (flags and AT_REMOVEDIR.inv() != 0) return -22
        val host = if (flags and AT_REMOVEDIR != 0) (if (darwin) 0x80 else 0x200) else 0
        return path(path) { name -> val state = errnoState.get(); status(state, LibcDowncall.UNLINKAT.invokeExact(Unlinkat.at, state, dirfd(dfd), name, host) as Int) }
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
            val result = status(state, LibcDowncall.STATX.invokeExact(Statx.at, state, dirfd(dfd), arena.allocateFrom(path), flags, mask, buffer) as Int)
            if (result == 0) MemorySegment.copy(buffer, 0, MemorySegment.ofArray(out), at.toLong(), STATX_SIZE.toLong())
            return result
        }
        val stat = arena.allocate(144, 8)
        val result = if (path.isEmpty()) {
            if (flags and AT_EMPTY_PATH == 0) return -2
            status(state, LibcDowncall.FSTAT.invokeExact(Fstat.at, state, dfd, stat) as Int)
        } else status(state, LibcDowncall.FSTATAT.invokeExact(Fstatat.at, state, dirfd(dfd), arena.allocateFrom(path), stat,
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
    fun getdents(fd: Int, out: ByteArray, at: Int, length: Int): Int = native(maxOf(1, length)) { native ->
        val state = errnoState.get()
        if (linux) {
            val result = status(state, LibcDowncall.SYSCALL.invokeExact(Syscall.at, state, if (arm64) 61L else 217L, fd.toLong(), native, length.toLong()) as Long)
            if (result > 0) MemorySegment.copy(native, 0, MemorySegment.ofArray(out), at.toLong(), result.toLong())
            return result
        }
        val result = Arena.ofConfined().use { arena ->
            status(state, LibcDowncall.GETDIRENTRIES.invokeExact(Getdirentries.at, state, fd, native, length.toLong(), arena.allocate(long)) as Long)
        }
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
