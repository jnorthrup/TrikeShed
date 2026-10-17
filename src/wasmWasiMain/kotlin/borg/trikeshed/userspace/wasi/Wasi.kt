@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmInterop::class)

package borg.trikeshed.userspace.wasi

import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.MemoryAllocator
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// wasi_snapshot_preview1: every pointer is an i32 address in this module's linear memory; every call returns a WASI errno.
@WasmImport("wasi_snapshot_preview1", "fd_prestat_get") private external fun fdPrestatGet(fd: Int, buf: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_prestat_dir_name") private external fun fdPrestatDirName(fd: Int, path: Int, len: Int): Int
@WasmImport("wasi_snapshot_preview1", "path_open")
private external fun pathOpen(fd: Int, dirflags: Int, path: Int, len: Int, oflags: Int, base: Long, inheriting: Long, fdflags: Int, opened: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_pread") private external fun fdPread(fd: Int, iovs: Int, count: Int, offset: Long, nread: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_pwrite") private external fun fdPwrite(fd: Int, iovs: Int, count: Int, offset: Long, nwritten: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_write") private external fun fdWrite(fd: Int, iovs: Int, count: Int, nwritten: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_close") private external fun fdClose(fd: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_datasync") private external fun fdDatasync(fd: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_filestat_get") private external fun fdFilestatGet(fd: Int, buf: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_fdstat_get") private external fun fdFdstatGet(fd: Int, buf: Int): Int
@WasmImport("wasi_snapshot_preview1", "fd_readdir") private external fun fdReaddir(fd: Int, buf: Int, len: Int, cookie: Long, used: Int): Int
@WasmImport("wasi_snapshot_preview1", "path_filestat_get")
private external fun pathFilestatGet(fd: Int, flags: Int, path: Int, len: Int, buf: Int): Int
@WasmImport("wasi_snapshot_preview1", "path_create_directory") private external fun pathCreateDirectory(fd: Int, path: Int, len: Int): Int
@WasmImport("wasi_snapshot_preview1", "path_unlink_file") private external fun pathUnlinkFile(fd: Int, path: Int, len: Int): Int
@WasmImport("wasi_snapshot_preview1", "path_remove_directory") private external fun pathRemoveDirectory(fd: Int, path: Int, len: Int): Int
@WasmImport("wasi_snapshot_preview1", "path_rename")
private external fun pathRename(fd: Int, old: Int, oldLen: Int, newFd: Int, new: Int, newLen: Int): Int
@WasmImport("wasi_snapshot_preview1", "clock_time_get") private external fun clockTimeGet(id: Int, precision: Long, time: Int): Int
@WasmImport("wasi_snapshot_preview1", "random_get") private external fun randomGet(buf: Int, len: Int): Int
@WasmImport("wasi_snapshot_preview1", "environ_sizes_get") private external fun environSizesGet(count: Int, size: Int): Int
@WasmImport("wasi_snapshot_preview1", "environ_get") private external fun environGet(environ: Int, buf: Int): Int
@WasmImport("wasi_snapshot_preview1", "args_sizes_get") private external fun argsSizesGet(count: Int, size: Int): Int
@WasmImport("wasi_snapshot_preview1", "args_get") private external fun argsGet(argv: Int, buf: Int): Int

/** A WASI filestat: filetype, size and modification time (ns). */
class WasiStat(val filetype: Int, val size: Long, val mtimNanos: Long) {
    val isDirectory: Boolean get() = filetype == Wasi.FILETYPE_DIRECTORY
    val isRegular: Boolean get() = filetype == Wasi.FILETYPE_REGULAR
}

/**
 * WASI preview 1 host calls in Linux terms: results are a value or a negative Linux errno, paths are absolute or
 * relative to the first preopened directory, and a path resolves through the preopen with the longest matching name.
 */
object Wasi {
    const val FILETYPE_DIRECTORY = 3
    const val FILETYPE_REGULAR = 4
    private const val CLOCK_REALTIME = 0
    private const val CLOCK_MONOTONIC = 1
    private const val EBADF_WASI = 8

    private const val RIGHTS_READ = (1L shl 1) or (1L shl 2) or (1L shl 5) or (1L shl 7) or (1L shl 14) or (1L shl 21) or 1L or (1L shl 4)
    private const val RIGHTS_WRITE = RIGHTS_READ or (1L shl 6) or (1L shl 8) or (1L shl 22) or (1L shl 23)
    /** Directory rights a path_open may pass on: PATH_* and FD_READDIR, plus the file rights (bits 0..28, preview 1). */
    private const val RIGHTS_INHERITING = (1L shl 29) - 1

    /** Preopened directories: (fd, name), in descriptor order. */
    val preopens: List<Pair<Int, String>> by lazy {
        val found = ArrayList<Pair<Int, String>>()
        withScopedMemoryAllocator { a ->
            val stat = a.allocate(8)
            var fd = 3
            while (fdPrestatGet(fd, stat.i) == 0) {
                if (stat.loadByte().toInt() == 0) {
                    val len = (stat + 4).loadInt()
                    val name = a.allocate(len)
                    if (fdPrestatDirName(fd, name.i, len) == 0) found += fd to name.string(len).trimEnd('\u0000')
                }
                fd++
            }
        }
        found
    }

    /** The working directory: the first preopen's name, "/" when the host preopened nothing. */
    val cwd: String get() = preopens.firstOrNull()?.second ?: "/"

    /** (preopen fd, path relative to it) for [path]; null when no preopen holds it. */
    fun resolve(path: String): Pair<Int, String>? {
        val absolute = if (path.startsWith("/")) path else "${cwd.trimEnd('/')}/$path"
        var best: Pair<Int, String>? = null
        var bestLength = -1
        for ((fd, name) in preopens) {
            val root = name.trimEnd('/')
            val inside = absolute == root || absolute.startsWith("$root/") || root.isEmpty() || root == "."
            if (inside && root.length > bestLength) {
                bestLength = root.length
                best = fd to absolute.removePrefix(root).trimStart('/').ifEmpty { "." }
            }
        }
        if (best == null && !path.startsWith("/")) preopens.firstOrNull()?.let { best = it.first to path.ifEmpty { "." } }
        return best
    }

    /** OPENAT with Linux O_* [flags]: the new fd, or -errno. */
    fun open(path: ByteArray, flags: Int): Int {
        val (dir, relative) = resolve(path.decodeToString()) ?: return -2
        var oflags = 0
        if (flags and 0x40 != 0) oflags = oflags or 1
        if (flags and 0x10000 != 0) oflags = oflags or 2
        if (flags and 0x80 != 0) oflags = oflags or 4
        if (flags and 0x200 != 0) oflags = oflags or 8
        val fdflags = if (flags and 0x400 != 0) 1 else 0
        val lookup = if (flags and 0x20000 != 0) 0 else 1
        val rights = if (flags and 0x10000 != 0) RIGHTS_INHERITING else if (flags and 3 == 0) RIGHTS_READ else RIGHTS_WRITE
        return withScopedMemoryAllocator { a ->
            val p = a.bytes(relative.encodeToByteArray())
            val out = a.allocate(4)
            val errno = pathOpen(dir, lookup, p.i, relative.encodeToByteArray().size, oflags, rights, if (flags and 0x10000 != 0) RIGHTS_INHERITING else rights, fdflags, out.i)
            if (errno == 0) out.loadInt() else -linuxErrno(errno)
        }
    }

    /** pread into [dst][[at], [at] + [len]) from file [offset]: bytes read, or -errno. Only the bytes read are stored. */
    fun pread(fd: Int, dst: ByteArray, at: Int, len: Int, offset: Long): Int = withScopedMemoryAllocator { a ->
        val buf = a.allocate(maxOf(len, 1))
        val iov = a.iov(buf, len)
        val n = a.allocate(4)
        val errno = fdPread(fd, iov.i, 1, offset, n.i)
        if (errno != 0) -linuxErrno(errno) else n.loadInt().also { buf.copyTo(dst, at, it) }
    }

    /** pwrite of [src][[at], [at] + [len]) at file [offset]: bytes written, or -errno. */
    fun pwrite(fd: Int, src: ByteArray, at: Int, len: Int, offset: Long): Int = withScopedMemoryAllocator { a ->
        val buf = a.allocate(maxOf(len, 1))
        buf.copyFrom(src, at, len)
        val iov = a.iov(buf, len)
        val n = a.allocate(4)
        val errno = fdPwrite(fd, iov.i, 1, offset, n.i)
        if (errno != 0) -linuxErrno(errno) else n.loadInt()
    }

    /** fd_write of [src] to [fd] (1 = stdout, 2 = stderr): bytes written, or -errno. */
    fun write(fd: Int, src: ByteArray): Int = withScopedMemoryAllocator { a ->
        val buf = a.allocate(maxOf(src.size, 1))
        buf.copyFrom(src, 0, src.size)
        val iov = a.iov(buf, src.size)
        val n = a.allocate(4)
        val errno = fdWrite(fd, iov.i, 1, n.i)
        if (errno != 0) -linuxErrno(errno) else n.loadInt()
    }

    fun close(fd: Int): Int = fdClose(fd).let { if (it == 0) 0 else -linuxErrno(it) }
    fun datasync(fd: Int): Int = fdDatasync(fd).let { if (it == 0) 0 else -linuxErrno(it) }
    fun isOpen(fd: Int): Boolean = withScopedMemoryAllocator { a -> fdFdstatGet(fd, a.allocate(24).i) != EBADF_WASI }

    fun stat(fd: Int): WasiStat? = withScopedMemoryAllocator { a ->
        val buf = a.allocate(64)
        if (fdFilestatGet(fd, buf.i) == 0) buf.filestat() else null
    }

    fun stat(path: String): WasiStat? {
        val (dir, relative) = resolve(path) ?: return null
        return withScopedMemoryAllocator { a ->
            val bytes = relative.encodeToByteArray()
            val p = a.bytes(bytes)
            val buf = a.allocate(64)
            if (pathFilestatGet(dir, 1, p.i, bytes.size, buf.i) == 0) buf.filestat() else null
        }
    }

    /** Names in directory [path], without "." and "..". */
    fun list(path: String): List<String> {
        val fd = open(path.encodeToByteArray(), 0x10000)
        if (fd < 0) return emptyList()
        val names = ArrayList<String>()
        try {
            withScopedMemoryAllocator { a ->
                val size = 4096
                val buf = a.allocate(size)
                val used = a.allocate(4)
                var cookie = 0L
                while (true) {
                    if (fdReaddir(fd, buf.i, size, cookie, used.i) != 0) break
                    val n = used.loadInt()
                    var at = 0
                    var advanced = false
                    while (at + 24 <= n) {
                        val next = (buf + at).loadLong()
                        val nameLength = (buf + at + 16).loadInt()
                        if (at + 24 + nameLength > n) break
                        val name = (buf + at + 24).string(nameLength)
                        if (name != "." && name != "..") names += name
                        cookie = next
                        advanced = true
                        at += 24 + nameLength
                    }
                    if (n < size || !advanced) break
                }
            }
        } finally {
            close(fd)
        }
        return names
    }

    fun mkdir(path: String): Int = pathCall(path) { dir, p, len -> pathCreateDirectory(dir, p, len) }
    fun unlink(path: String): Int = pathCall(path) { dir, p, len -> pathUnlinkFile(dir, p, len) }
    fun rmdir(path: String): Int = pathCall(path) { dir, p, len -> pathRemoveDirectory(dir, p, len) }

    fun rename(src: String, dst: String): Int {
        val (fromDir, from) = resolve(src) ?: return -2
        val (toDir, to) = resolve(dst) ?: return -2
        return withScopedMemoryAllocator { a ->
            val f = from.encodeToByteArray()
            val t = to.encodeToByteArray()
            val errno = pathRename(fromDir, a.bytes(f).i, f.size, toDir, a.bytes(t).i, t.size)
            if (errno == 0) 0 else -linuxErrno(errno)
        }
    }

    fun realtimeNanos(): Long = clock(CLOCK_REALTIME)
    fun monotonicNanos(): Long = clock(CLOCK_MONOTONIC)

    fun random(bytes: ByteArray) {
        var at = 0
        while (at < bytes.size) {
            val count = minOf(65536, bytes.size - at)
            withScopedMemoryAllocator { a ->
                val buf = a.allocate(count)
                check(randomGet(buf.i, count) == 0) { "random_get failed" }
                buf.copyTo(bytes, at, count)
            }
            at += count
        }
    }

    /** The process environment as NAME=value strings. */
    val environment: Map<String, String> by lazy { strings({ c, s -> environSizesGet(c, s) }, { e, b -> environGet(e, b) }).associate {
        val eq = it.indexOf('=')
        if (eq < 0) it to "" else it.substring(0, eq) to it.substring(eq + 1)
    } }

    /** The program's arguments as their bytes, its name first. */
    val arguments: List<ByteArray> by lazy { blobs({ c, s -> argsSizesGet(c, s) }, { v, b -> argsGet(v, b) }) }

    private fun clock(id: Int): Long = withScopedMemoryAllocator { a ->
        val t = a.allocate(8)
        check(clockTimeGet(id, 1L, t.i) == 0) { "clock_time_get($id) failed" }
        t.loadLong()
    }

    private fun strings(sizes: (Int, Int) -> Int, get: (Int, Int) -> Int): List<String> = blobs(sizes, get).map { it.decodeToString() }

    private fun blobs(sizes: (Int, Int) -> Int, get: (Int, Int) -> Int): List<ByteArray> = withScopedMemoryAllocator { a ->
        val count = a.allocate(4)
        val size = a.allocate(4)
        if (sizes(count.i, size.i) != 0) return@withScopedMemoryAllocator emptyList()
        val n = count.loadInt()
        val pointers = a.allocate(maxOf(4 * n, 4))
        val buf = a.allocate(maxOf(size.loadInt(), 1))
        if (get(pointers.i, buf.i) != 0) return@withScopedMemoryAllocator emptyList()
        List(n) { index ->
            val start = (pointers + 4 * index).loadInt()
            var end = start
            while (Pointer(end.toUInt()).loadByte().toInt() != 0) end++
            ByteArray(end - start) { Pointer((start + it).toUInt()).loadByte() }
        }
    }

    private inline fun pathCall(path: String, call: (Int, Int, Int) -> Int): Int {
        val (dir, relative) = resolve(path) ?: return -2
        return withScopedMemoryAllocator { a ->
            val bytes = relative.encodeToByteArray()
            val errno = call(dir, a.bytes(bytes).i, bytes.size)
            if (errno == 0) 0 else -linuxErrno(errno)
        }
    }

    /** WASI errno -> Linux errno. */
    fun linuxErrno(wasi: Int): Int = when (wasi) {
        1 -> 7; 2 -> 13; 3 -> 98; 4 -> 99; 5 -> 97; 6 -> 11; 7 -> 114; 8 -> 9; 9 -> 74; 10 -> 16; 11 -> 125; 12 -> 10
        13 -> 103; 14 -> 111; 15 -> 104; 16 -> 35; 17 -> 89; 18 -> 33; 19 -> 122; 20 -> 17; 21 -> 14; 22 -> 27
        23 -> 113; 24 -> 43; 25 -> 84; 26 -> 115; 27 -> 4; 28 -> 22; 29 -> 5; 30 -> 106; 31 -> 21; 32 -> 40; 33 -> 24
        34 -> 31; 35 -> 90; 36 -> 72; 37 -> 36; 38 -> 100; 39 -> 102; 40 -> 101; 41 -> 23; 42 -> 105; 43 -> 19
        44 -> 2; 45 -> 8; 46 -> 37; 47 -> 67; 48 -> 12; 49 -> 42; 50 -> 92; 51 -> 28; 52 -> 38; 53 -> 107; 54 -> 20
        55 -> 39; 56 -> 131; 57 -> 88; 58 -> 95; 59 -> 25; 60 -> 6; 61 -> 75; 62 -> 130; 63 -> 1; 64 -> 32; 65 -> 71
        66 -> 93; 67 -> 91; 68 -> 34; 69 -> 30; 70 -> 29; 71 -> 3; 72 -> 116; 73 -> 110; 74 -> 26; 75 -> 18; 76 -> 13
        else -> 5
    }
}

private val Pointer.i: Int get() = address.toInt()

private fun MemoryAllocator.bytes(bytes: ByteArray): Pointer = allocate(maxOf(bytes.size, 1)).also { it.copyFrom(bytes, 0, bytes.size) }

private fun MemoryAllocator.iov(buf: Pointer, len: Int): Pointer = allocate(8).also {
    it.storeInt(buf.i)
    (it + 4).storeInt(len)
}

private fun Pointer.string(len: Int): String = ByteArray(len) { (this + it).loadByte() }.decodeToString()

private fun Pointer.filestat(): WasiStat = WasiStat((this + 16).loadByte().toInt(), (this + 32).loadLong(), (this + 48).loadLong())

/** Linear memory -> [dst][[at], [at] + [len]): four bytes per load where the run allows. */
private fun Pointer.copyTo(dst: ByteArray, at: Int, len: Int) {
    var i = 0
    while (i + 4 <= len) {
        val v = (this + i).loadInt()
        dst[at + i] = v.toByte(); dst[at + i + 1] = (v shr 8).toByte(); dst[at + i + 2] = (v shr 16).toByte(); dst[at + i + 3] = (v shr 24).toByte()
        i += 4
    }
    while (i < len) { dst[at + i] = (this + i).loadByte(); i++ }
}

/** [src][[at], [at] + [len]) -> linear memory: four bytes per store where the run allows. */
private fun Pointer.copyFrom(src: ByteArray, at: Int, len: Int) {
    var i = 0
    while (i + 4 <= len) {
        (this + i).storeInt((src[at + i].toInt() and 0xff) or ((src[at + i + 1].toInt() and 0xff) shl 8) or
            ((src[at + i + 2].toInt() and 0xff) shl 16) or (src[at + i + 3].toInt() shl 24))
        i += 4
    }
    while (i < len) { (this + i).storeByte(src[at + i]); i++ }
}
