@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.common.Files
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.MingwUserspaceChannelBackend
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix._fstat64
import platform.posix._stat64
import platform.posix.errno

/** FileOperations over C runtime descriptors (binary mode) and [Files]. */
class MingwFileOperations : FileOperations {
    override fun open(path: String, readOnly: Boolean): Int {
        val fd = platform.posix.open(path, MingwUserspaceChannelBackend.openFlags(
            if (readOnly) UringOp.O_RDONLY else UringOp.O_RDWR or UringOp.O_CREAT), 420)
        return if (fd < 0) -errno else fd
    }
    override fun close(fd: Int): Int = if (platform.posix.close(fd) == 0) 0 else -errno
    override fun size(fd: Int): Long = memScoped {
        val st = alloc<_stat64>()
        if (_fstat64(fd, st.ptr) == 0) st.st_size else -1L
    }
    override fun readAllLines(filename: String) = Files.readAllLines(filename)
    override fun readAllBytes(filename: String) = Files.readAllBytes(filename)
    override fun readString(filename: String) = Files.readString(filename)
    override fun write(filename: String, bytes: ByteArray) { Files.write(filename, bytes) }
    override fun write(filename: String, lines: List<String>) { Files.write(filename, lines) }
    override fun write(filename: String, string: String) { Files.write(filename, string) }
    override fun cwd() = Files.cwd()
    override fun exists(filename: String) = Files.exists(filename)
    override fun lastModified(filename: String): Long = Files.lastModified(filename)
    override fun streamLines(fileName: String, bufsize: Int) = Files.streamLines(fileName, bufsize)
    override fun iterateLines(fileName: String, bufsize: Int) = Files.iterateLines(fileName, bufsize)
    override fun listDir(path: String) = Files.listDir(path)
    override fun isDir(path: String) = Files.isDir(path)
    override fun isFile(path: String) = Files.isFile(path)
    override fun mkdirs(path: String) { Files.mkdirs(path) }
    override fun deleteRecursively(path: String) { Files.deleteRecursively(path) }
    override fun resolvePath(vararg parts: String) = Files.resolvePath(*parts)
    override fun readZip(path: String) = Files.readZip(path)
    override fun createTempDir(prefix: String) = Files.createTempDir(prefix)
}
