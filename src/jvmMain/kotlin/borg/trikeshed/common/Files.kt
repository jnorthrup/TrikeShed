package borg.trikeshed.common

import java.nio.file.Files
import java.nio.file.Paths

actual object Files {
    actual fun readAllLines(filename: String): List<String>  = Files.readAllLines(Paths.get(filename))
    actual fun readAllBytes(filename: String): ByteArray  = Files.readAllBytes(Paths.get(filename))
    actual fun readString(filename: String): String = Files.readString(Paths.get(filename))
    actual fun write(filename: String, bytes: ByteArray)   {
        Files.write(Paths.get(filename), bytes)
    }
    actual fun write(filename: String, lines: List<String>) {
        Files.write(Paths.get(filename), lines)
    }
    actual fun write(filename: String, string: String) {
        Files.writeString(Paths.get(filename), string)
    }

    actual fun cwd(): String  = Paths.get("").toAbsolutePath().toString()

    actual fun exists(filename: String): Boolean = Files.exists(Paths.get(filename))
}