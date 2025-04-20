@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.io

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import kotlinx.cinterop.*
import platform.posix._PC_PATH_MAX
import platform.posix.getcwd
import platform.posix.pathconf
import simple.PosixFile

actual object Files {
    actual fun readAllLines(filename: String): List<String> = readLines(filename)
    actual fun readAllBytes(filename: String): ByteArray = PosixFile.readAllBytes(filename)
    actual fun readString(filename: String): String = PosixFile.readString(filename)
    actual fun write(filename: String, bytes: ByteArray): Unit = PosixFile.writeBytes(filename, bytes).let { }
    actual fun write(filename: String, lines: List<String>): Unit = PosixFile.writeLines(filename, lines)
    actual fun write(filename: String, string: String): Unit = PosixFile.writeString(filename, string).let { }

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

    actual fun exists(filename: String): Boolean = PosixFile.exists(filename)

    /** read offsets and lines accompanying*/
    actual fun streamLines(
        fileName: String,
        bufsize: Int,
    ): Sequence<Join<Long, ByteArray>> = sequence {
        val file = PosixFile(fileName)
        val fp = fdopen(file.fd, "r")
        val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = alloc()
        val len: ULongVarOf<size_t> = alloc()
        len.value = 0u
        var read: ssize_t
        var offset = 0L

        memScoped {
            while (true) {
                read = getline(line.ptr, len.ptr, fp)
                if (read == -1L) break
                val lineStr = line.value!!.toKString().trim()
                yield(Join(offset, lineStr.encodeToByteArray()))
                offset += read
            }
            free(line.value)
            if (ferror(fp) != 0) {
                perror("ferror")
                throw IllegalStateException("Error reading file stream")
            }
        }
        file.close()
        fclose(fp)
    }

    actual fun iterateLines(
        fileName: String,
        bufsize: Int,
    ): Iterable<Join<Long, Series<Byte>>> = Iterable {
        val file = PosixFile(fileName)
        val fp = fdopen(file.fd, "r")
        val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = alloc()
        val len: ULongVarOf<size_t> = alloc()
        len.value = 0u
        var read: ssize_t
        var offset = 0L
        val result = mutableListOf<Join<Long, Series<Byte>>>()

        memScoped {
            while (true) {
                read = getline(line.ptr, len.ptr, fp)
                if (read == -1L) break
                val lineStr = line.value!!.toKString().trim()
                result.add(Join(offset, lineStr.encodeToByteArray().toSeries()))
                offset += read
            }
            free(line.value)
            if (ferror(fp) != 0) {
                perror("ferror")
                throw IllegalStateException("Error reading file stream")
            }
        }
        file.close()
        fclose(fp)
        result.iterator()
    }
}
