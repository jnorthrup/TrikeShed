package borg.trikeshed.isam

import borg.trikeshed.common.readLines
import simple.PosixFile

actual object Files {
    actual fun readAllLines(filename: String)= readLines(filename)
    actual fun readAllBytes(filename: String): ByteArray = PosixFile.readAllBytes(filename)
    actual fun readString(filename: String): String = PosixFile.readString(filename)
    actual fun write( filename: String, bytes: ByteArray   ) = PosixFile.writeBytes(filename, bytes).let {  }
    actual fun write( filename: String, lines: List<String> )= PosixFile.writeLines(  filename, lines)
    actual fun write( filename: String, string: String      ) = PosixFile.writeString(filename, string).let {  }
}
