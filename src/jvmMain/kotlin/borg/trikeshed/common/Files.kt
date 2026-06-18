@file:OptIn(ExperimentalTime::class)

package borg.trikeshed.common

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
                if (!tharr.decodeToString().trim().isEmpty())
                    yield((lineStartOffset j tharr))
            }
        }
    }

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> {
        val file = File(fileName)
        val input = file.inputStream()
        val rowLogger = FibonacciReporter(noun = "lines"/*, verb = "read"*/)
        var counter = 0
        val theIterable = object : Iterable<Join<Long, Series<Byte>>> {
            var fileClosed = false
            var accum: MutableList<BFrag> = mutableListOf()
            var recycler: LinkedHashSet<ByteArray> = linkedSetOf()
            var recycleHit = 0
            var recycleMiss = 0
            var curBuf: BFrag? = null
            var curlinepos = 0L
            fun drainAccum(tail: BFrag? = null): ByteArray {
                val appendum = tail?.run { a.b - a.a } ?: 0
                val ret = ByteArray(appendum + accum.sumOf { (a: Twin<Int>, _) -> val (c, d) = a; d - c })
                var offset = 0
                for (frag in accum) {
                    val (beg, end) = frag.a
                    frag.b.copyInto(ret, offset, beg, end)
                    offset += end - beg
                    if (tail?.b !== frag.b && curBuf?.b !== frag.b) recycler.add(frag.b)
                }

                if (tail != null) {
                    val (beg, end) = tail.a
                    tail.b.copyInto(ret, offset, beg, end)
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
                        val byteArray = unrecycle()

                        val read = input.read(byteArray)
                        if (read != -1)
                            curBuf = ((0 j read) j byteArray) // same as flip
                        else {
                            fileClosed = true
                            curBuf = null

                            /* our state: accum: ?, curBuf: null, EOF: yes */
                            accum = accum.filterNot { it.a.run { a == b } }
                                .toMutableList()/* our state: accum: ?, curBuf: null, EOF: yes */
                            if (accum.isEmpty()) return null
                            val drainAccum = drainAccum()/* our state:  accum: empty, curBuf: null, EOF: yes */
                            if (drainAccum.isEmpty()) return null
                            return curlinepos j drainAccum.toSeries().debug {
                                curlinepos += it.size
                                rowLogger.close()
                                //print the total bytes(curlinepos), average bytes per line,avg lines/sec, avg bytes/s,  recycler hit/miss ratio and total calls, unrecycled buffers and bufsize*unrecycled, all using Long.humanReadableIEC
                                //also print the total time taken to read the file
                                val elapsedNow = rowLogger.begin.elapsedNow()
                                val totalSeconds = elapsedNow.inWholeSeconds.toLong()
                                println(
                                    "total bytes read: ${curlinepos.humanReadableByteCountIEC}" +
                                            "\n average bytes per line: ${(curlinepos / counter).humanReadableByteCountIEC}" +
                                            "\n average lines per second: ${
                                                ((counter / totalSeconds)).humanReadableByteCountIEC.replace(
                                                    "iB",
                                                    "L"
                                                )
                                            }" +
                                            "\n average bytes per second: ${((curlinepos / totalSeconds)).humanReadableByteCountIEC}" +
                                            "\n recycler miss ratio: ${recycleMiss.toFloat() / recycleHit.toFloat()}" +
                                            "\n total calls to recycler: ${recycleHit + recycleMiss}" +
                                            "\n unrecycled buffers: ${recycler.size}" +
                                            "\n unrecycled space in buffers: ${(recycler.size * bufsize).toLong().humanReadableByteCountSI}" +
                                            "\n total time taken to read the file: ${elapsedNow} seconds"
                                )


                            }/* our state:  accum: empty, curBuf: null, EOF: yes */
                        }
                    }

                    val theBuff = curBuf!!
                    //right here we should be at the split  point of the line. we either drain the accum and keep a curBuff
                    //or accumulate the whole remaining buffer and null out curBuf and loop back until newline or eof
                    theBuff.split1('\n'.code.toByte()).let { (succes: BFrag?, carry: BFrag?) ->
                        if (succes != null) {

                            curBuf = carry
                            val result = drainAccum(succes)

                            curlinepos += result.size
                            return curlinepos j result.toSeries()
                                .debug { rowLogger.report()?.also { println(it) } }
                        } else {
                            /* our state: accum: ?, curBuf: non-null, EOF: no */
                            accum.add(theBuff)
                            curBuf = null
                        }
                    }


                } while (true)
            }

            private fun unrecycle(): ByteArray {
                val iterator = recycler.takeUnless { it.isEmpty() }?.iterator()
                val attempt = iterator?.next()
                val byteArray = attempt?.also { iterator.remove() } ?: ByteArray(bufsize)
                //update hit and miss counters
                if (attempt != null) recycleHit++ else recycleMiss++
                return byteArray
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