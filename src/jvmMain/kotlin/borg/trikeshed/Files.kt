@file:OptIn(ExperimentalTime::class)

package borg.trikeshed

import borg.trikeshed.lib.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.ExperimentalTime
import java.nio.file.Files as JavaNioFileFiles
import java.nio.file.Paths as JavaNioFilePaths


actual object Files {
    actual fun readAllLines(filename: String): List<String> =
        JavaNioFileFiles.readAllLines(JavaNioFilePaths.get(filename))

    actual fun readAllBytes(filename: String): ByteArray = JavaNioFileFiles.readAllBytes(JavaNioFilePaths.get(filename))
    actual fun readString(filename: String): String = JavaNioFileFiles.readString(JavaNioFilePaths.get(filename))
    actual fun write(filename: String, bytes: ByteArray) {
        JavaNioFileFiles.write(JavaNioFilePaths.get(filename), bytes)
    }

    actual fun write(filename: String, lines: List<String>) {
        JavaNioFileFiles.write(JavaNioFilePaths.get(filename), lines)
    }

    actual fun write(filename: String, string: String) {
        JavaNioFileFiles.writeString(JavaNioFilePaths.get(filename), string)
    }

    actual fun cwd(): String = JavaNioFilePaths.get("").toAbsolutePath().toString()

    actual fun exists(filename: String): Boolean = JavaNioFileFiles.exists(JavaNioFilePaths.get(filename))

    actual fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> = sequence {
        val file = File(fileName)
        val buffer = ByteArray(bufsize)
        var offset: Long = 0
        var lineStartOffset: Long = 0
        val lineBuffer = ByteArrayOutputStream()

        file.inputStream().use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                var mark = 0
                for (i in 0 until bytesRead) {
                    val byte = buffer[i]
                    when (byte) {
                        '\n'.code.toByte() -> {
                            lineBuffer.write(buffer, mark, i - mark + 1)
                            mark = i + 1

                            yield(lineStartOffset j lineBuffer.toByteArray())
                            lineBuffer.reset()
                            lineStartOffset = offset + i + 1
                        }
                    }
                }
                if (mark < bytesRead) {
                    lineBuffer.write(buffer, mark, bytesRead - mark)
                }

                offset += bytesRead
            }

            if (lineBuffer.size() > 0) {
                val tharr = lineBuffer.toByteArray()
                yield(lineStartOffset j tharr)
            }
        }
    }

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> = TODO("iterateLines JVM")
}

}

fun main() {
    val lines = Files.iterateLines("/etc/default/grub", 128 * 1024)
    for (line in lines) {
        val (offset, lineBytes) = line
        val lineStr = lineBytes.asString()
        println("$offset: $lineStr")
    }
}
