package borg.trikeshed.common

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Join
import kotlinx.cinterop.*
import platform.posix._PC_PATH_MAX
import platform.posix.getcwd
import platform.posix.pathconf
import simple.PosixFile

actual object Files {
    actual fun readAllLines(filename: String): List<String> = readLines(filename)
    actual fun readAllBytes(filename: String): ByteArray = PosixFile.readAllBytes(filename)
    actual fun readString(filename: String): String = PosixFile.readString(filename)
    actual fun write(filename: String, bytes: ByteArray) = PosixFile.writeBytes(filename, bytes).let { }
    actual fun write(filename: String, lines: List<String>) = PosixFile.writeLines(filename, lines)
    actual fun write(filename: String, string: String) = PosixFile.writeString(filename, string).let { }

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
    actual fun streamLines(fileName: String): Sequence<Join<Long, ByteArray>> {
        TODO()
    }
}