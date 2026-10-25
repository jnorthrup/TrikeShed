@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

import borg.trikeshed.common.createTempDirectory
import borg.trikeshed.userspace.nio.posix.trikeshed_last_modified
import borg.trikeshed.userspace.nio.posix.trikeshed_file_lease
import borg.trikeshed.lib.*
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import platform.posix.*

class PosixFileOperations : FileOperations {

    override val durability: StorageDurability get() = StorageDurability.DURABLE

    override fun acquireExclusiveLease(path: String): FileLease {
        require('\u0000' !in path) { "Filesystem lease path contains NUL" }
        val fd = trikeshed_file_lease(path)
        check(fd >= 0) { posixFailure("acquire exclusive lease: $path") }
        return object : FileLease {
            val gate = SynchronizedObject()
            var closed = false
            override fun close() = synchronized(gate) {
                if (!closed) {
                    closed = true
                    check(platform.posix.close(fd) == 0) { posixFailure("close lease: $path") }
                }
            }
        }
    }

    /** Same-directory publication; no acknowledgement before file and directory fsync. */
    override fun writeAtomically(filename: String, bytes: ByteArray): Unit = memScoped {
        val name = filename.substringAfterLast('/')
        require('\u0000' !in filename && name.isNotEmpty() && name != "." && name != "..") {
            "Invalid atomic write target: $filename"
        }
        val target = if (filename.startsWith('/')) filename else "${cwd()}/$filename"
        val parent = target.substringBeforeLast('/').ifEmpty { "/" }
        mkdirs(parent)
        val template = "$parent/.trikeshed-XXXXXX".cstr.placeTo(this)
        val fd = mkstemp(template)
        check(fd >= 0) { posixFailure("mkstemp: $parent") }
        val temporary = template.toKString()
        var failure: Throwable? = null
        try {
            withDescriptor(fd, temporary) {
                check(fcntl(it, F_SETFD, FD_CLOEXEC) == 0) { posixFailure("FD_CLOEXEC: $temporary") }
                if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val written = platform.posix.write(it, pinned.addressOf(offset), (bytes.size - offset).convert())
                        if (written < 0 && errno == EINTR) continue
                        check(written > 0) { posixFailure("write: $temporary") }
                        offset += written.toInt()
                    }
                }
                syncDescriptor(it, temporary)
            }
            check(rename(temporary, target) == 0) { posixFailure("rename: $temporary -> $target") }
            syncDirectory(parent)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (unlink(temporary) != 0 && errno != ENOENT) {
                val cleanup = IllegalStateException(posixFailure("unlink: $temporary"))
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    fun posixFailure(operation: String): String = "$operation failed (errno=$errno): ${strerror(errno)?.toKString()}"

    inline fun <T> withDescriptor(fd: Int, path: String, body: (Int) -> T): T {
        check(fd >= 0) { posixFailure("open: $path") }
        var failure: Throwable? = null
        try {
            return body(fd)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (platform.posix.close(fd) != 0) {
                val cleanup = IllegalStateException(posixFailure("close: $path"))
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    fun syncDescriptor(fd: Int, path: String) {
        var result: Int
        do { result = fsync(fd) } while (result != 0 && errno == EINTR)
        check(result == 0) { posixFailure("fsync: $path") }
    }

    fun syncDirectory(path: String) =
        withDescriptor(platform.posix.open(path, O_RDONLY or O_DIRECTORY or O_CLOEXEC), path) { syncDescriptor(it, path) }

    override fun readAllLines(filename: String): List<String> =
        readString(filename).replace("\r\n", "\n").split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }

    override fun readAllBytes(filename: String): ByteArray = memScoped {
        val file = fopen(filename, "rb") ?: throw IllegalArgumentException("fopen($filename) failed")
        try {
            require(fseek(file, 0, SEEK_END) == 0)
            val size = ftell(file); require(size >= 0)
            require(fseek(file, 0, SEEK_SET) == 0)
            if (size == 0L) return ByteArray(0)
            val bytes = ByteArray(size.toInt())
            val read = bytes.usePinned { fread(it.addressOf(0), 1.convert(), size.toULong(), file) }
            if (read.toLong() == size) bytes else bytes.copyOf(read.toInt())
        } finally { fclose(file) }
    }

    override fun readString(filename: String): String = readAllBytes(filename).decodeToString()

    override fun write(filename: String, bytes: ByteArray) {
        val file = fopen(filename, "wb") ?: throw IllegalArgumentException("fopen($filename) failed")
        try {
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.toULong(), file) }
                require(written.toInt() == bytes.size)
            }
        } finally { fclose(file) }
    }

    override fun write(filename: String, lines: List<String>) { write(filename, lines.joinToString("\n")) }
    override fun write(filename: String, string: String) { write(filename, string.encodeToByteArray()) }

    override fun cwd(): String = memScoped {
        val pathmax = pathconf(".", _PC_PATH_MAX)
        val buf: CPointer<ByteVarOf<Byte>> = allocArray(pathmax.toInt())
        getcwd(buf, pathmax.toULong())?.toKString() ?: ""
    }

    override fun exists(filename: String): Boolean = access(filename, F_OK) == 0

    override fun lastModified(filename: String): Long = trikeshed_last_modified(filename)

    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> {
        val bytes = readAllBytes(fileName)
        return sequence {
            if (bytes.isEmpty()) return@sequence
            var start = 0; var index = 0
            while (index < bytes.size) {
                if (bytes[index] == '\n'.code.toByte()) {
                    yield(start.toLong() j bytes.copyOfRange(start, index + 1))
                    start = index + 1
                }
                index++
            }
            if (start < bytes.size) yield(start.toLong() j bytes.copyOfRange(start, bytes.size))
        }
    }

    override fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (off, bytes) -> off j bytes.toSeries() }.asIterable()

    override fun listDir(path: String): List<String> = memScoped {
        val dp = opendir(path) ?: return@memScoped emptyList()
        try {
            val result = mutableListOf<String>()


            while (true) { val ent = readdir(dp) ?: break; result.add(ent.pointed.d_name.toKString()) }
            result.filter { it != "." && it != ".." }
        } finally { closedir(dp) }
    }

    override fun isDir(path: String): Boolean = memScoped {
        val st = alloc<stat>(); stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    override fun isFile(path: String): Boolean = memScoped {
        val st = alloc<stat>(); stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFREG
    }

    override fun mkdirs(path: String) {
        require(path.isNotEmpty() && '\u0000' !in path) { "Invalid directory path" }
        if (isDir(path)) return
        val directory = path.trimEnd('/')
        val parent = directory.substringBeforeLast('/', ".").ifEmpty { "/" }
        mkdirs(parent)
        check(mkdir(directory, 0x1FFu.convert()) == 0 || (errno == EEXIST && isDir(directory))) {
            posixFailure("mkdir: $directory")
        }
        syncDirectory(directory)
        syncDirectory(parent)
    }

    /** mkdir(2) of one absent directory: an existing leaf, a symlink included, answers EEXIST and refuses. */
    override fun createDirectory(path: String) {
        require(path.isNotEmpty() && '\u0000' !in path) { "Invalid directory path" }
        val directory = path.trimEnd('/').ifEmpty { "/" }
        check(mkdir(directory, 0x1FFu.convert()) == 0) { posixFailure("mkdir: $directory") }
        syncDirectory(directory.substringBeforeLast('/', ".").ifEmpty { "/" })
    }

    /**
     * Move without replacing. A file moves by link(2) then unlink(2), so an existing destination refuses atomically
     * (EEXIST); a directory, which link(2) cannot name, is checked absent and renamed.
     */
    override fun move(source: String, destination: String) {
        require('\u0000' !in source && '\u0000' !in destination) { "Invalid move path" }
        val parent = destination.trimEnd('/').substringBeforeLast('/', ".").ifEmpty { "/" }
        if (isDir(source)) {
            check(!exists(destination)) { "move: $destination exists" }
            check(rename(source, destination) == 0) { posixFailure("rename: $source -> $destination") }
        } else {
            check(link(source, destination) == 0) { posixFailure("link: $source -> $destination") }
            check(unlink(source) == 0) { posixFailure("unlink: $source") }
        }
        syncDirectory(parent)
        val origin = source.trimEnd('/').substringBeforeLast('/', ".").ifEmpty { "/" }
        if (origin != parent) syncDirectory(origin)
    }
    override fun deleteRecursively(path: String): Unit = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return // Does not exist
        if ((st.st_mode.toInt() and S_IFMT) == S_IFDIR) {
            val dp = opendir(path) ?: return
            try {


                while (true) {
                    val ent = readdir(dp) ?: break

                    val name = ent.pointed.d_name.toKString()
                    if (name != "." && name != "..") {
                        deleteRecursively("$path/$name")
                    }
                }
            } finally {
                closedir(dp)
            }
            rmdir(path)
        } else {
            remove(path)
        }
    }
    override fun resolvePath(vararg parts: String): String {
        val joined = parts.asSequence().filter { it.isNotEmpty() }.joinToString("/")
        require('\u0000' !in joined) { "Filesystem path contains NUL" }
        val absolute = if (joined.startsWith('/')) joined else "${cwd()}/$joined"
        val names = mutableListOf<String>()
        for (name in absolute.splitToSequence('/')) when (name) {
            "", "." -> Unit
            ".." -> if (names.isNotEmpty()) names.removeAt(names.lastIndex)
            else -> names.add(name)
        }
        return "/" + names.joinToString("/")
    }

    override fun canonicalPath(path: String): String {
        require(path.isNotEmpty() && '\u0000' !in path) { "Invalid filesystem path" }
        var candidate = (if (path.startsWith('/')) path else "${cwd()}/$path").trimEnd('/').ifEmpty { "/" }
        val missing = mutableListOf<String>()
        while (true) {
            val resolved = realpath(candidate, null)
            if (resolved != null) {
                try { return resolvePath(resolved.toKString(), *missing.asReversed().toTypedArray()) }
                finally { free(resolved) }
            }
            check(errno == ENOENT) { posixFailure("realpath: $candidate") }
            // A dangling symlink is an existing, unresolved alias, not a new directory name.
            val absent = memScoped { lstat(candidate, alloc<stat>().ptr) }
            check(absent != 0) { "Unresolvable filesystem path: $candidate" }
            check(errno == ENOENT && candidate != "/") { posixFailure("lstat: $candidate") }
            val name = candidate.substringAfterLast('/')
            require(name != "..") { "Unresolvable filesystem path: $candidate" }
            if (name != ".") missing.add(name)
            candidate = candidate.substringBeforeLast('/').trimEnd('/').ifEmpty { "/" }
        }
    }

    override fun readZip(path: String): List<Join<String, ByteArray>> = throw UnsupportedOperationException("readZip unsupported")
    override fun open(path: String, readOnly: Boolean): Int {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return platform.posix.open(path, flags,  644.fromOctal())
    }

    override fun close(fd: Int): Int = platform.posix.close(fd)

    override fun size(fd: Int): Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) == 0) st.st_size else -1L
    }

    override fun createTempDir(prefix: String): String = createTempDirectory(prefix)
}
