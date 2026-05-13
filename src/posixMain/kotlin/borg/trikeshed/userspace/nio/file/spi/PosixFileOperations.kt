@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlinx.cinterop.*
import platform.posix.*

class PosixFileOperations : FileOperations {

    override fun readAllLines(filename: CharSequence): Series<CharSequence> =
        readString(filename).toString().replace("\r\n", "\n").split('\n').let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }.toSeries()

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

    override fun readString(filename: CharSequence): CharSequence = readAllBytes(filename).decodeToString()

    override fun write(filename: CharSequence, bytes: ByteArray) {
        val file = fopen(filename.toString(), "wb") ?: throw IllegalArgumentException("fopen($filename) failed")
        try {
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.toULong(), file) }
                require(written.toInt() == bytes.size)
            }
        } finally { fclose(file) }
    }

    override fun write(filename: CharSequence, string: CharSequence) { write(filename, string.toString().encodeToByteArray()) }
    override fun write(filename: CharSequence, lines: Series<CharSequence>) { write(filename, lines.view.joinToString("\n")) }

    override fun cwd(): CharSequence = memScoped {
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

    override fun iterateLines(fileName: CharSequence, bufsize: Int): Iterable<Join<Long, ByteArray>> =
        streamLines(fileName, bufsize).asIterable()

    override fun listDir(path: CharSequence): List<CharSequence> = memScoped {
        val dp = opendir(path.toString()) ?: return@memScoped emptyList()
        try {
            val result = mutableListOf<CharSequence>()
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
    override fun resolvePath(vararg parts: CharSequence): CharSequence = parts.joinToString("/")
    override fun readZip(path: CharSequence): Series2<CharSequence, ByteArray> = TODO("readZip POSIX")
    override fun createTempDir(prefix: CharSequence): CharSequence = memScoped {
        val tmpl = "/tmp/${prefix}XXXXXX"
        val buf = tmpl.cstr.ptr
        mkdtemp(buf)?.toKString() ?: error("mkdtemp failed")
    }
}
