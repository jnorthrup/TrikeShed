package borg.trikeshed.isam

import java.nio.file.Paths
import java.nio.file.Files as  NioFiles


actual object Files {
    actual fun readAllLines(filename: String): List<String>  =NioFiles.readAllLines(Paths.get(filename))
    actual fun readAllBytes(filename: String): ByteArray  =NioFiles.readAllBytes(Paths.get(filename))
    actual fun readString(filename: String): String =NioFiles.readString(Paths.get(filename))
    actual fun write(filename: String, bytes: ByteArray)   { NioFiles.write(Paths.get(filename), bytes) }
    actual fun write(filename: String, lines: List<String>) { NioFiles.write(Paths.get(filename), lines) }
    actual fun write(filename: String, string: String) { NioFiles.writeString(Paths.get(filename), string) }
    actual fun cwd(): String  = Paths.get("").toAbsolutePath().toString()

    actual fun exists(filename: String): Boolean = NioFiles.exists(Paths.get(filename))
}