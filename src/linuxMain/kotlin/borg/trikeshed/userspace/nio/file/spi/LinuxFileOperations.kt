@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.PosixUringIO
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.common.createTempDirectory
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.fromOctal
import kotlinx.cinterop.*
import platform.posix.*

class LinuxFileOperations : FileOperations {

    override fun readAllLines(filename: String): List<String> =
        readString(filename).replace("\r\n", "\n").split('\n').let {
            if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it
        }

    override fun readAllBytes(filename: String): ByteArray = withFile(filename, 0) { facade, fd ->
        val size = PosixUringIO.fileSize(fd)
        check(size in 0..Int.MAX_VALUE.toLong()) { "invalid file size: $size" }
        val bytes = ByteArray(size.toInt())
        val buffer = ByteBuffer(bytes)
        while (buffer.hasRemaining()) {
            val read = execute(facade, Submissions.read(fd, 0, buffer.remaining(), buffer.position().toLong(), 0)
                .copy(buffer = buffer))
            check(read >= 0) { "read failed on $filename: $read" }
            if (read == 0) break
        }
        if (buffer.position() == bytes.size) bytes else bytes.copyOf(buffer.position())
    }

    override fun readString(filename: String): String = readAllBytes(filename).decodeToString()

    override fun write(filename: String, bytes: ByteArray) = withFile(filename, 1 or 64 or 512) { facade, fd ->
        val buffer = ByteBuffer(bytes)
        while (buffer.hasRemaining()) {
            val written = execute(facade, Submissions.write(fd, 0, buffer.remaining(), buffer.position().toLong(), 0)
                .copy(buffer = buffer))
            check(written > 0) { "write failed on $filename: $written" }
        }
    }

    private fun execute(facade: FunctionalUringFacade, submission: UringSubmission): Int {
        facade.enqueue(submission)
        facade.submit()
        val completion = facade.wait().single()
        check(completion.userData == submission.userData)
        return completion.res
    }

    private inline fun <T> withFile(path: String, flags: Int, block: (FunctionalUringFacade, Int) -> T): T {
        val facade = FunctionalUringFacade(2, openUserspaceChannelBackend(2))
        try {
            val fd = execute(facade, Submissions.openat(path, flags))
            check(fd >= 0) { "open failed on $path: $fd" }
            return try { block(facade, fd) }
            finally { execute(facade, Submissions.close(fd, 0)) }
        } finally { facade.closeNow() }
    }

    override fun write(filename: String, lines: List<String>) { write(filename, lines.joinToString("\n")) }
    override fun write(filename: String, string: String) { write(filename, string.encodeToByteArray()) }

    override fun cwd(): String = memScoped {
        val pathmax = pathconf(".", _PC_PATH_MAX)
        val buf: CPointer<ByteVar> = allocArray(pathmax.toInt())
        getcwd(buf, pathmax.toULong())?.toKString() ?: ""
    }

    override fun exists(filename: String): Boolean = access(filename, F_OK) == 0

    override fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> {
        val bytes = readAllBytes(fileName)
        return sequence {
            if (bytes.isEmpty()) return@sequence
            var start = 0
            var index = 0
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


            while (true) {
                val ent = readdir(dp) ?: break
                result.add(ent.pointed.d_name.toKString())
            }
            result.filter { it != "." && it != ".." }
        } finally {
            closedir(dp)
        }
    }

    override fun isDir(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    override fun isFile(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFREG
    }

    override fun mkdirs(path: String) { mkdir(path, 0x1FFu.convert()) }
    override fun deleteRecursively(path: String) {
        memScoped {
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
    }
    override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
    override fun readZip(path: String): List<Join<String, ByteArray>> = throw UnsupportedOperationException("readZip unsupported")
    override fun createTempDir(prefix: String): String = createTempDirectory(prefix)

    override fun open(path: String, readOnly: Boolean): Int {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return platform.posix.open(path, flags, 644.fromOctal())
    }

    override fun close(fd: Int): Int = platform.posix.close(fd)

    override fun size(fd: Int): Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) == 0) st.st_size else -1L
    }
}
