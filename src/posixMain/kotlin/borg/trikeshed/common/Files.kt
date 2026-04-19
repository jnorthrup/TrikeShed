@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlinx.cinterop.*
import platform.posix.*

actual object Files {
    actual fun readAllLines(filename: String): List<String> =
        readString(filename).replace("\r\n", "\n").split('\n').let { parts ->
            if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        }

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


}
