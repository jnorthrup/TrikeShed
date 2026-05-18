@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import platform.posix.*

class PosixFileOperations : FileOperations {

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
            val buf = alloc<dirent>()
            val entry = allocPointerTo<dirent>()
            while (true) { if (readdir_r(dp, buf.ptr, entry.ptr) != 0) break; entry.value?.let { result.add(it.pointed.d_name.toKString()) } ?: break }
            result.filter { it != "." && it != ".." }
        } finally { closedir(dp) }
    }

    override fun isDir(path: String): Boolean = memScoped {
        val st = alloc<stat>(); stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    override fun isFile(path: String): Boolean = memScoped {
        val st = alloc<stat>(); stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFREG
    }

    override fun mkdirs(path: String) { mkdir(path, 0x1FFu.toUShort()) }
    override fun deleteRecursively(path: String): Unit = TODO("deleteRecursively POSIX")
    override fun resolvePath(vararg parts: String): String = parts.joinToString("/")
    override fun readZip(path: String): List<Pair<String, ByteArray>> = TODO("readZip POSIX")
    override fun open(path: String, readOnly: Boolean): Int {
        val flags = if (readOnly) O_RDONLY else (O_RDWR or O_CREAT)
        return platform.posix.open(path, flags,  644.fromOctal())
    }

    override fun close(fd: Int): Int = platform.posix.close(fd)

    override fun size(fd: Int): Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) == 0) st.st_size else 0L
    }

    override fun createTempDir(prefix: String): String = "/tmp/$prefix-${generateSequence { ('a'..'z').random() }.take(8).joinToString("")}"
}
