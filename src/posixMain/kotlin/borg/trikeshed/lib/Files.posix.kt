@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.lib

import kotlinx.cinterop.*
import platform.posix.*

actual object Files {
    actual fun readAllLines(filename: String): List<String> =
        readString(filename).replace("\r\n", "\n").split('\n').let { parts ->
            if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        }

    @OptIn(ExperimentalForeignApi::class)
    actual fun readAllBytes(filename: String): ByteArray = memScoped {
        val file = fopen(filename, "rb") ?: throw IllegalArgumentException("fopen($filename) failed")
        try {
            require(fseek(file, 0, SEEK_END) == 0) { "fseek($filename, SEEK_END) failed" }
            val size = ftell(file)
            require(size >= 0) { "ftell($filename) failed" }
            require(fseek(file, 0, SEEK_SET) == 0) { "fseek($filename, SEEK_SET) failed" }
            if (size == 0L) return ByteArray(0)

            val bytes = ByteArray(size.toInt())
            val read = bytes.usePinned { pinned ->
                fread(pinned.addressOf(0), 1.convert(), size.toULong(), file)
            }
            return if (read.toLong() == size) bytes else bytes.copyOf(read.toInt())
        } finally {
            fclose(file)
        }
    }

    actual fun readString(filename: String): String = readAllBytes(filename).decodeToString()

    actual fun write(filename: String, bytes: ByteArray) {
        val file = fopen(filename, "wb") ?: throw IllegalArgumentException("fopen($filename) failed")
        try {
            if (bytes.isNotEmpty()) {
                val written = bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1.convert(), bytes.size.toULong(), file)
                }
                require(written.toInt() == bytes.size) { "fwrite($filename) failed" }
            }
        } finally {
            fclose(file)
        }
    }

    actual fun write(filename: String, lines: List<String>) {
        write(filename, lines.joinToString("\n"))
    }

    actual fun write(filename: String, string: String) {
        write(filename, string.encodeToByteArray())
    }

    /**cinterop to get cwd from posix */
    actual fun cwd(): String = memScoped {
        // cinterop pathmax to get the max path length
        val pathmax = pathconf(".", _PC_PATH_MAX)
        // allocate a buffer of that size
        val buf: CPointer<ByteVarOf<Byte>> = allocArray(pathmax.toInt())
        // get the cwd into the buffer
        val cwd: CPointer<ByteVarOf<Byte>>? = getcwd(buf, pathmax.toULong())
        // convert the buffer to a string
        cwd?.toKString() ?: ""
    }

    actual fun exists(filename: String): Boolean = access(filename, F_OK) == 0

    /** read offsets and lines accompanying*/
    actual fun streamLines(
        fileName: String,
        bufsize: Int,
    ): Sequence<Join<Long, ByteArray>> {
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
            if (start < bytes.size) {
                yield(start.toLong() j bytes.copyOfRange(start, bytes.size))
            }
        }
    }

    actual fun iterateLines(
        fileName: String,
        bufsize: Int,
    ): Iterable<Join<Long, Series<Byte>>> {
        return streamLines(fileName, bufsize).map { (offset, bytes) -> offset j bytes.toSeries() }.asIterable()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun listDir(path: String): List<String> =
        memScoped {
            val dp = opendir(path) ?: return@memScoped emptyList<String>()
            try {
                val result = mutableListOf<String>()
                val buf = alloc<dirent>()
                var entry = allocPointerTo<dirent>()
                while (true) {
                    val err = readdir_r(dp, buf.ptr, entry.ptr)
                    if (err != 0) break
                    val ent = entry.value
                    if (ent == null) break
                    val name = ent.pointed.d_name.toKString()
                    if (name != "." && name != "..") result.add(name)
                }
                result
            } finally {
                closedir(dp)
            }
        }

    actual fun isDir(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun isFile(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFREG
    }

    actual fun mkdirs(path: String) {
        mkdir(path, 0x1FFu.toUShort())
    }

    actual fun deleteRecursively(path: String): Unit = TODO("deleteRecursively POSIX")

    actual fun resolvePath(vararg parts: String): String = parts.joinToString("/")

    actual fun readZip(path: String): List<Pair<String, ByteArray>> = TODO("readZip POSIX")

    actual fun createTempDir(prefix: String): String =
        "/tmp/$prefix-${generateSequence { ('a'..'z').random() }.take(8).joinToString("")}"


}
