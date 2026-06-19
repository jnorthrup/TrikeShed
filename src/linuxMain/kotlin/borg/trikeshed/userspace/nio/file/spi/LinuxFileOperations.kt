@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.PosixUringIO
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

    override fun readAllBytes(filename: String): ByteArray = memScoped {
        val fd = open(filename, O_RDONLY)
        if (fd < 0) throw IllegalArgumentException("open($filename) failed")
        try {
            val size = PosixUringIO.fileSize(fd)
            if (size <= 0) return ByteArray(0)
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = PosixUringIO.readAt(fd, bytes, offset, bytes.size - offset, offset.toLong())
                if (read <= 0) break
                offset += read
            }
            if (offset == bytes.size) bytes else bytes.copyOf(offset)
        } finally {
            PosixUringIO.closeFd(fd)
        }
    }

    override fun readString(filename: String): String = readAllBytes(filename).decodeToString()

    override fun write(filename: String, bytes: ByteArray) {
        val fd = open(filename, O_WRONLY or O_CREAT or O_TRUNC, 438) // 0666
        if (fd < 0) throw IllegalArgumentException("open($filename) failed")
        try {
            var offset = 0
            while (offset < bytes.size) {
                val written = PosixUringIO.writeAt(fd, bytes, offset, bytes.size - offset, offset.toLong())
                if (written <= 0) break
                offset += written
            }
            require(offset == bytes.size) { "short write on $filename: $offset/${bytes.size}" }
        } finally {
            PosixUringIO.closeFd(fd)
        }
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
    override fun readZip(path: String): List<Pair<String, ByteArray>> = throw UnsupportedOperationException("readZip unsupported")
    override fun createTempDir(prefix: String): String =
        "/tmp/$prefix-${generateSequence { ('a'..'z').random() }.take(8).joinToString("")}"

    override fun open(path: String, readOnly: Boolean): Int {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return platform.posix.open(path, flags, 644.fromOctal())
    }

    override fun close(fd: Int): Int = platform.posix.close(fd)

    override fun size(fd: Int): Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) == 0) st.st_size else 0L
    }
}
