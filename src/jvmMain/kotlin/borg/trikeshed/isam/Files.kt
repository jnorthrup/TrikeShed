package borg.trikeshed.isam

import java.nio.file.Paths


actual object Files {
    actual fun readAllLines(filename: String): List<String>  =Files.readAllLines(filename)

    actual fun readAllBytes(filename: String): ByteArray  =Files.readAllBytes(filename)

    actual fun readString(filename: String): String =Files.readString(filename)

    actual fun writeAllBytes(filename: String, bytes: ByteArray)   { Files.writeAllBytes(filename, bytes) }
    actual fun writeAllLines(filename: String, lines: List<String>) {
        Files.writeAllLines(filename, lines)
    }
    actual fun writeString(filename: String, string: String) { Files.writeString(filename, string) }
}