package borg.trikeshed.common

import borg.trikeshed.lib.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> {
        val input = FileInputStream(fileName)

        /** a fragment in accum is a buffer which ends before EOL. */
        val accum: MutableList<BFrag> = mutableListOf()

        /** we only create bufSize arrays, and recycle them.*/
        val recycle: MutableList<ByteArray> = mutableListOf()
        var currentLineStart = 0L

        /** when EOL occurs in the buffer we have to remember the start of the next line. */
        var curBuff: ByteArray? = null
        var fClosed = false
        var pending: BFrag? = null
        fun fragMerge(): Join<Long, Series<Byte>> {
            val lineLen = accum.sumOf { (bound, b) ->
                val (f, l) = bound
                l - f
            }
            //if linelen is at least 2/3 of the buffer size, we can use the recycle list.
            val buf =
                if (bufsize > lineLen && (bufsize - lineLen) < bufsize / 3 && recycle.isNotEmpty()) recycle.removeLast()
                else ByteArray(lineLen)
            var pos = 0
            for ((bound, b) in accum) {
                val (f, l) = bound
                b.copyInto(buf, pos, f, l)
                pos += l - f
                if (b !== curBuff) recycle.add(b)
            }
            accum.clear()
            //if pending twin is not empty, we have to add it to the accum list.
            if (pending != null) {
                val (bound: Twin<Int>) = pending!!
                val (f, l) = bound
                if (f < l) accum.add(pending!!)
                pending = null
            }

            val retval: Join<Long, Series<Byte>> = currentLineStart j (lineLen j buf::get)
            currentLineStart += lineLen
            return retval
        }

        fun readNextLine(): Join<Long, Series<Byte>>? {
            if (fClosed) return null
            curBuff = curBuff ?: recycle.lastOrNull() ?: ByteArray(bufsize)
            val read = input.read(curBuff!!)
            if (read == -1) {
                fClosed = true
                if (accum.isNotEmpty()) return fragMerge() else
                    return null
            }
            val byteSeries: ByteSeries = ByteSeries(read j curBuff!!::get)
            if (byteSeries.seekTo('\n'.code.toByte())) {
                //create 2 BFrag objects, one for the line, one for the remainder.
                val join = 0 j byteSeries.pos
                val complete: BFrag = join j curBuff!!
                pending = (byteSeries.pos.inc() j read) j curBuff!!
                accum.add(complete)
                return fragMerge()
            } else {
                //if we are here, we have not found EOL in the buffer.
                accum.add(0 j read j curBuff!!) //add the whole buffer to the accum list.
                return null
            }
        }
        return object : Iterable<Join<Long, Series<Byte>>> {
            override fun iterator(): Iterator<Join<Long, Series<Byte>>> {
                return object : Iterator<Join<Long, Series<Byte>>> {
                    var nextLine: Join<Long, Series<Byte>>? = null
                    override fun hasNext(): Boolean {
                        if (nextLine == null) nextLine = readNextLine()
                        return nextLine != null
                    }

                    override fun next(): Join<Long, Series<Byte>> {
                        if (nextLine == null) nextLine = readNextLine()
                        val retval = nextLine!!
                        nextLine = null
                        return retval
                    }
                }
            }
        }
    }
}

fun main() {
    val lines = Files.iterateLines("/etc/default/grub", 1024)
    for (line in lines) {
        val (offset, lineBytes) = line
        val lineStr = lineBytes.asString()
        println("$offset: $lineStr")
    }
}