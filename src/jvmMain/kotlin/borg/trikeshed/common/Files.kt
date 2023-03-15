package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.second
import java.io.File
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
        val lineBuffer = mutableListOf<ByteArray>()

        file.inputStream().use { input ->
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                var mark = 0
                for (i in 0 until bytesRead) {
                    val byte = buffer[i]
                    when (byte) {
                        '\n'.code.toByte() -> {
                            lineBuffer.add(buffer.sliceArray(mark..i))
                            mark = i

                            // Sum the sizes of the ByteArrays in lineBuffer and create a new ByteArray of that size
                            val totalSize = lineBuffer.sumOf { it.size }
                            val tharr = ByteArray(totalSize)

                            // Copy the elements from each ByteArray in lineBuffer into tharr
                            var index = 0
                            for (ba in lineBuffer) {
                                ba.copyInto(tharr, index)
                                index += ba.size
                            }

                            yield(lineStartOffset j tharr)
                            lineBuffer.clear()
                            lineStartOffset = offset + i + 1
                        }
                    }
                }
                lineBuffer.add(buffer.sliceArray(mark until bytesRead))

                offset += bytesRead
            }

            if (lineBuffer.isNotEmpty()) {
                // Sum the sizes of the ByteArrays in lineBuffer and create a new ByteArray of that size
                val totalSize = lineBuffer.sumOf { it.size }
                val tharr = ByteArray(totalSize)

                // Copy the elements from each ByteArray in lineBuffer into tharr
                var index = 0
                for (ba in lineBuffer) {
                    ba.copyInto(tharr, index)
                    index += ba.size
                }

                if (!tharr.decodeToString().trim().isEmpty())
                    yield((lineStartOffset j tharr))
            }
        }
    }}

/** unit test for fun streamLines*/
fun main() {
//write several lines in a  tmpfile
    val tmpfile = File.createTempFile("test", ".txt")

    val lines = listOf("line1", "line2", "line3")

    JavaNioFileFiles.write(tmpfile.toPath(), lines)
    tmpfile.deleteOnExit()

    //read the lines back in a sequence
    val seq = Files.streamLines(tmpfile.absolutePath, 1)
    seq.forEach { println(it.second.decodeToString()) }
    println("lines should be 3 - lines are ${seq.count()}")
    assert(seq.map { it.second.toString(Charsets.UTF_8) }.toList() == lines) { "lines differ" }
}

