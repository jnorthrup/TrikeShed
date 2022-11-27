package borg.trikeshed.isam

import borg.trikeshed.common.readLines
import borg.trikeshed.native.HasDescriptor
import kotlinx.cinterop.memScoped
import simple.PosixFile

actual object Files {
    actual fun readAllLines(filename: String)= readLines(filename)
    actual fun readAllBytes(filename: String): ByteArray = PosixFile.readAllBytes(filename)
    actual fun readString(filename: String): String = PosixFile.readString(filename)
    actual fun writeAllBytes(filename: String, bytes: ByteArray) = PosixFile.writeAllBytes(filename, bytes)
    actual fun writeAllLines(filename: String, lines: List<String>) = PosixFile.writeAllLines(  filename, lines)
    actual fun writeString(filename: String, string: String) = PosixFile.writeString(filename, string)
}
