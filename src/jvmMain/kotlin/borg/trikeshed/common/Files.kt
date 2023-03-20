package borg.trikeshed.common

import borg.trikeshed.lib.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files as JavaNioFileFiles
import java.nio.file.Paths as JavaNioFilePaths

typealias BFrag = Join<Twin<Int>, ByteArray>

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
                if (!tharr.decodeToString().trim().isEmpty())
                    yield((lineStartOffset j tharr))
            }
        }
    }

    /**probably leaks a file handle here*/
    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> {

        val file = File(fileName)
        val input = file.inputStream()
        val theIterable = object : Iterable<Join<Long, Series<Byte>>> {
            var fileClosed = false
            val accum: MutableList<ByteArray> = mutableListOf()
            var curBuf: ByteArray? = null
            var curlinepos = 0L
            fun drainAccum(): ByteArray {

                val ret = ByteArray(accum.sumOf { it.size })
                var offset = 0
                for (a in accum) {
                    a.copyInto(ret, offset)
                    offset += a.size
                }
                accum.clear()
                return ret
            }

            fun newLine(): Join<Long, Series<Byte>>? {
                do {
                    if (fileClosed) return null
                    if (curBuf == null) {
                        curBuf = ByteArray(bufsize)
                        val read = input.read(curBuf)
                        if (read == -1) {
                            fileClosed = true
                            curBuf = null
                        }
                    }
                    if (curBuf == null) {
                        val drainAccum = drainAccum()
                        if (drainAccum.isEmpty()) return null
                        return curlinepos j drainAccum.toSeries()
                    }

                    //right here we should be at the split  point of the line. we either drain the accum and keep a curBuff
                    //or accumulate the whole remaining buffer and null out curBuf and loop back until newline or eof
                    var mark = 0
                    ByteSeries(curBuf!!).let { bs ->
                        if (bs.seekTo('\n'.code.toByte())) {
                            val carry = bs.slice
                            val terminus = bs.flip()

                            accum += terminus.run { ByteArray(rem, ::get) }
                            curBuf = carry.run { ByteArray(rem, ::get) }
                            val drainAccum = drainAccum()
                            val r = curlinepos j drainAccum.toSeries()
                            curlinepos += drainAccum.size
                            return r
                        } else {
                            accum += curBuf!!
                            curBuf = null
                        }
                    }

                } while (true)


            }

            override fun iterator(): Iterator<Join<Long, Series<Byte>>> {

                return object : Iterator<Join<Long, Series<Byte>>> {
                    var nextLine: Join<Long, Series<Byte>>? = null
                    override fun hasNext(): Boolean {
                        if (nextLine == null) nextLine = newLine()
                        return nextLine != null
                    }

                    override fun next(): Join<Long, Series<Byte>> {
                        if (nextLine == null) nextLine = newLine()
                        val retval = nextLine!!
                        nextLine = null
                        return retval
                    }
                }
            }
        }
        return theIterable
    }
}

fun main() {
    val lines = Files.iterateLines("/etc/default/grub", 25)
    for (line in lines) {
        val (offset, lineBytes) = line
        val lineStr = lineBytes.asString()
        println("$offset: $lineStr")
    }
}