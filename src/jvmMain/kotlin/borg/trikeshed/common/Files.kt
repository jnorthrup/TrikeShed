package borg.trikeshed.common

import borg.trikeshed.lib.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files as JavaNioFileFiles
import java.nio.file.Paths as JavaNioFilePaths

typealias BFrag = Join<
        /**endexclusive range*/
        Twin<Int>, ByteArray>

fun BFrag.isEmpty() = a.run { a == b }
fun BFrag.slice(atInclusive: Int, untilExclusive: Int = a.b) = a.run { a + atInclusive j untilExclusive } j b

//as in ByteBuffer.flip after a read
fun BFrag.flip(bytesRead: Int) = a.run { a j a + bytesRead } j b
fun ByteSeries(BFragment: BFrag) = ByteSeries(BFragment.b, BFragment.a.a, BFragment.a.b)

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

    const val debugForNulls = true


    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> {
        val rowLogger = FibonacciReporter(noun = "writes"/*, verb = "read"*/)
        val file = File(fileName)
        val input = file.inputStream()
        var counter = 0
        val theIterable = object : Iterable<Join<Long, Series<Byte>>> {
            var fileClosed = false
            var accum: MutableList<BFrag> = mutableListOf()
            var curBuf: BFrag? = null
            var curlinepos = 0L
            var curFrag: BFrag? = null
            fun drainAccum(): ByteArray {

                val ret = ByteArray(accum.sumOf { (a: Twin<Int>, _) -> val (c, d) = a; d - c })


                var offset = 0
                for (frag in accum) {
                    val (beg, end) = frag.a
                    frag.b.copyInto(ret, offset, beg, end)
                    offset += end - beg

                }
                accum.clear()
                return ret
            }

            fun newLine(): Join<Long, Series<Byte>>? {
                do {
                    /* our state: accum: ?, curBuf: ?, EOF: ? */

                    if (fileClosed) return null
                    /* our state: accum: ?, curBuf: ?, EOF: no */
                    if (curBuf == null) {
                        /* our state:  accum: ?, curBuf: null, EOF: no */
                        val byteArray = ByteArray(bufsize)
                        val read = input.read(byteArray)
                        if (read != -1)
                            curBuf = ((0 j read) j byteArray)
                        else {
                            fileClosed = true
                            curBuf = null

                            /* our state: accum: ?, curBuf: null, EOF: yes */
                            accum = accum.filterNot { it.a.run { a == b } }
                                .toMutableList()/* our state: accum: ?, curBuf: null, EOF: yes */

                            val drainAccum = drainAccum()/* our state:  accum: empty, curBuf: null, EOF: yes */
                            if (drainAccum.isEmpty()) return null
                            return curlinepos j drainAccum.toSeries()
                                .also { rowLogger.report() }/* our state:  accum: empty, curBuf: null, EOF: yes */
                        }
                    }

                    //right here we should be at the split  point of the line. we either drain the accum and keep a curBuff
                    //or accumulate the whole remaining buffer and null out curBuf and loop back until newline or eof
                    ByteSeries(curBuf!!).let { bs ->/* our state: accum: ?, curBuf: y, EOF: ? */
                        if (bs.seekTo('\n'.code.toByte())) {
                            val carry = curBuf!!.slice(bs.pos)
                            val terminus = curBuf!!.a.run { a j bs.pos + a } j curBuf!!.b

                            if (terminus.a.run { a < b }) accum += terminus /* our state: accum: +, curBuf: y, EOF: ? */
                            if (carry.a.run { a < b }) curBuf = carry else curBuf = null
                            val drainAccum = drainAccum()
                            val r = curlinepos j drainAccum.toSeries()
                            curlinepos += drainAccum.size
                            return r/* our state: accum: empty, curBuf: y, EOF: ? */.also { rowLogger.report() }
                        } else {/* our state: accum: ?, curBuf: y, EOF: ? */
                            if (curBuf!!.isEmpty()) return null/* our state: accum: ?, curBuf: y, EOF: ? */
                            val bs1: ByteSeries = ByteSeries(curBuf!!)
                            if (bs1.seekTo(0.toByte()))
                                bs1.flip()
                            if (bs1.hasRemaining) {/* our state: accum: ?, curBuf: y, EOF: ? */
                                curBuf = curBuf!!.a.run { a j bs1.pos + a } j curBuf!!.b
                                if (curBuf!!.isEmpty() || curBuf!!.run { b[a.a + 0] == 0.toByte() }) return null
                                accum += curBuf!!
                                curBuf = null
                            }
                        }/* our state: accum: ?, curBuf: y, EOF: ? */
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
                        val retval = nextLine!!.also { counter++ }
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
    val lines = Files.iterateLines("/etc/default/grub", 128 * 1024)
    for (line in lines) {
        val (offset, lineBytes) = line
        val lineStr = lineBytes.asString()
        println("$offset: $lineStr")
    }
}