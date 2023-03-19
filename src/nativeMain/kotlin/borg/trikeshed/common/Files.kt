package borg.trikeshed.common

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Join3
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
    ): Sequence<Join<Long, ByteArray>> {
        TODO("Not yet implemented")
    }

    actual fun iterateLines(
        fileName: String,
        bufsize: Int,
    ): Iterable<Join3<Long, ByteSeries, Boolean>> {
        TODO("Not yet implemented")
    }


}