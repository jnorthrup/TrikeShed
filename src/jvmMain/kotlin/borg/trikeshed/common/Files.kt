package borg.trikeshed.common

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.decodeToCharSeries
import borg.trikeshed.lib.*
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths

actual object Files {
    actual fun readAllLines(filename: String): List<String> = Files.readAllLines(Paths.get(filename))
    actual fun readAllBytes(filename: String): ByteArray = Files.readAllBytes(Paths.get(filename))
    actual fun readString(filename: String): String = Files.readString(Paths.get(filename))
    actual fun write(filename: String, bytes: ByteArray) {
        Files.write(Paths.get(filename), bytes)
    }

    actual fun write(filename: String, lines: List<String>) {
        Files.write(Paths.get(filename), lines)
    }

    actual fun write(filename: String, string: String) {
        Files.writeString(Paths.get(filename), string)
    }

    actual fun cwd(): String = Paths.get("").toAbsolutePath().toString()

    actual fun exists(filename: String): Boolean = Files.exists(Paths.get(filename))
    actual fun streamLines(fileName: String): Sequence<Join<Long, ByteArray>> {
        var outerPos = 0L
        var curLineStart = 0L
        val carry = mutableListOf<ByteArray>()
        val buf = ByteBuffer.allocate(64)
        return sequence {
            //we need a position tracking Channel for fifo1
            FileInputStream(fileName).channel.use { channel ->
                do {
                    val isClosed = channel.read(buf) < 1
                    val input = buf.flip()
                    outerPos += input.remaining().toLong()

                    var theByte = 0.toByte()
                    //scan for EOL. if we run out of buffer then copy the buf to carry and continue this loop.
                    while (!isClosed && input.hasRemaining() && (input.mark().get()
                            .also { theByte = it }) != '\n'.code.toByte()
                    );
                    if (curLineStart > outerPos) {
                        val byteArray = ByteArray(input.position())
                        input.array().copyInto(byteArray, 0, 0, input.position())   //copy the line into byteArray
                        byteArray.also { carry += it }
                        if (isClosed || theByte == '\n'.code.toByte()) {
                            curLineStart = outerPos

                            carry.sumOf { it.size }.let { size ->
                                val bytes = ByteArray(size)
                                var pos = 0
                                carry.forEach { b ->
                                    b.copyInto(bytes, pos, 0, b.size)
                                    pos += b.size
                                }
                                yield(curLineStart j bytes )
                            }
                            carry.clear()
                        }
                        input.compact()
                    }
                } while (!isClosed)
            }
        }
    }
}