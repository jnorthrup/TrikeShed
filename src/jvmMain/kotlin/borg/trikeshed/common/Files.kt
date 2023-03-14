package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import java.io.FileInputStream
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
    actual fun streamLines(fileName: String, bufsize:Int): Sequence<Join<Long, ByteArray>> {
        var outerPos = 0L
        var curLineStart = 0L
        val carry = mutableListOf<ByteArray>()
        val recycler=ArrayDeque<ByteArray>()
        val buf = ByteArray(bufsize)
        return sequence {
            FileInputStream(fileName).use { channel ->

                while (true) {
                    val read = channel.read(buf)
                    if (read == -1) break
                    var lineStart = 0
                    for (i in 0 until read) {
                        if (buf[i] == '\n'.code.toByte()) {
                            //if len==64 then use buf from recycler
                            if (i - lineStart == bufsize .dec()) {
                                if (recycler.isNotEmpty()) {
                                    val recycled = recycler.removeFirst()
                                    System.arraycopy(buf, lineStart, recycled, 0, bufsize)
                                    carry.add(recycled)
                                } else {
                                    carry.add(buf.copyOfRange(lineStart, i + 1))
                                }
                            } else {
                                carry.add(buf.copyOfRange(lineStart, i + 1))
                            }




                            if (carry.sumOf { it.size } > 0)
                                yield((curLineStart j carry.reduce { acc, bytes -> acc + bytes }))
                            //reclaim 64 byte buffers from carry into recycler
                            carry.forEach({ if (it.size == bufsize) recycler.addLast(it) })
                            carry.clear()
                            lineStart = i + 1
                            curLineStart = outerPos + lineStart
                        }
                    }
                    if (lineStart < read)
                        carry.add(buf.copyOfRange(lineStart, read))
                    outerPos += read

                }
                if (carry.isNotEmpty()) {
                    if (carry.sumOf { it.size } > 0)
                        yield(curLineStart j carry.reduce { acc, bytes -> acc + bytes })
                }
            }
        }
    }
}