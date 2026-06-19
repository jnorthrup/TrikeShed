package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Files as LibFiles

actual object Files {
    actual fun readAllLines(filename: String): List<String> = LibFiles.readAllLines(filename)
    actual fun readAllBytes(filename: String): ByteArray = LibFiles.readAllBytes(filename)
    actual fun readString(filename: String): String = LibFiles.readString(filename)
    actual fun write(filename: String, bytes: ByteArray) = LibFiles.write(filename, bytes)
    actual fun write(filename: String, lines: List<String>) = LibFiles.write(filename, lines)
    actual fun write(filename: String, string: String) = LibFiles.write(filename, string)
    actual fun cwd(): String = LibFiles.cwd()
    actual fun exists(filename: String): Boolean = LibFiles.exists(filename)

    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        LibFiles.streamLines(fileName, bufsize)

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        LibFiles.iterateLines(fileName, bufsize)
}
