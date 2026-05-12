@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import platform.posix.*

class PosixFileOperations : FileOperations {

    override fun readAllLines(filename: CharSequence): List<String> =
        readString(filename).replace("\r\n", "\n").split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }

    override fun readAllBytes(filename: CharSequence): ByteArray = memScoped {
        val file = fopen(filename.toString(), "rb") ?: throw IllegalArgumentException("fopen($filename) failed")
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

    override fun readString(filename: CharSequence): String = readAllBytes(filename).decodeToString()

    override fun write(filename: CharSequence, bytes: ByteArray) {
        val file = fopen(filename.toString(), "wb") ?: throw IllegalArgumentException("fopen($filename) failed")
        try {
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.toULong(), file) }
                require(written.toInt() == bytes.size)
            }
        } finally { fclose(file) }
    }

    override fun write(filename: CharSequence, lines: List<CharSequence>) { write(filename, lines.joinToString("\n")) }
    override fun write(filename: CharSequence, string: CharSequence) { write(filename, string.toString().encodeToByteArray()) }

    override fun cwd(): String = memScoped {
        val pathmax = pathconf(".", _PC_PATH_MAX)
        val buf: CPointer<ByteVarOf<Byte>> = allocArray(pathmax.toInt())
        getcwd(buf, pathmax.toULong())?.toKString() ?: ""
    }

    override fun exists(filename: CharSequence): Boolean = access(filename.toString(), F_OK) == 0

    override fun streamLines(fileName: CharSequence, bufsize: Int): Sequence<Join<Long, ByteArray>> {
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

    override fun iterateLines(fileName: CharSequence, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        streamLines(fileName, bufsize).map { (off, bytes) -> off j bytes.toSeries() }.asIterable()

    override fun listDir(path: CharSequence): List<String> = memScoped {
        val dp = opendir(path.toString()) ?: return@memScoped emptyList()
        try {
            val result = mutableListOf<String>()
            val buf = alloc<dirent>()
            var entry = allocPointerTo<dirent>()
            while (true) { if (readdir_r(dp, buf.ptr, entry.ptr) != 0) break; entry.value?.let { result.add(it.pointed.d_name.toKString()) } ?: break }
            result.filter { it != "." && it != ".." }
        } finally { closedir(dp) }
    }

    override fun isDir(path: CharSequence): Boolean = memScoped {
        val st = alloc<stat>(); stat(path.toString(), st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    override fun isFile(path: CharSequence): Boolean = memScoped {
        val st = alloc<stat>(); stat(path.toString(), st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFREG
    }

    override fun mkdirs(path: CharSequence) { mkdir(path.toString(), 0x1FFu.toUShort()) }
    override fun deleteRecursively(path: CharSequence): Unit = TODO("deleteRecursively POSIX")
    override fun resolvePath(vararg parts: CharSequence): String = parts.joinToString("/")
    override fun readZip(path: CharSequence): List<Pair<String, ByteArray>> = TODO("readZip POSIX")
    override fun createTempDir(prefix: CharSequence): String = "/tmp/$prefix-${generateSequence { ('a'..'z').random() }.take(8).joinToString("")}"
}
