package borg.trikeshed.common

import borg.trikeshed.lib.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.assert
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

    actual fun iterateLines(fileName: String, bufsize: Int): Iterable<Join3<Long, ByteSeries, Boolean>> {
        val file = File(fileName)
        val buffer = ByteArray(bufsize)
        var offset: Long = 0
        var lineStartOffset: Long = 0
        val lineBuffer = ByteArrayOutputStream()

        return object : Iterable<Join3<Long, ByteSeries, Boolean>> {
            override fun iterator(): Iterator<Join3<Long, ByteSeries, Boolean>> {
                val input = file.inputStream()
                var nextValue: Join3<Long, ByteSeries, Boolean>? = null


                fun readNext(): Join3<Long, ByteSeries, Boolean>? {
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

                                    val s = lineBuffer.toByteArray()
                                    val byteSeries = ByteSeries(s)
                                    val result: Join3<Long, ByteSeries, Boolean> =
                                        lineStartOffset j byteSeries x dirtyUtf8Bytes(byteSeries)
                                    lineStartOffset = offset + i + 1
                                    offset += bytesRead
                                    return result
                                }
                            }
                        }
                        if (mark < bytesRead) {
                            lineBuffer.write(buffer, mark, bytesRead - mark)
                        }

                        offset += bytesRead
                    }

                    if (lineBuffer.size() > 0) {
                        val barray = lineBuffer.toByteArray()
                        val tmp: ByteSeries = ByteSeries(barray.toSeries())
                        val dirty = dirtyUtf8Bytes(tmp)
                        if (!tmp.trim.isEmpty()) return lineStartOffset j tmp x dirty
                    }

                    input.close()
                    return null
                }

                return object : Iterator<Join3<Long, ByteSeries, Boolean>> {
                    override fun hasNext(): Boolean = nextValue ?: readNext().also { nextValue = it } != null

                    override fun next(): Join3<Long, ByteSeries, Boolean> = when {
                        hasNext() -> {
                            val result: Join3<Long, ByteSeries, Boolean> = nextValue!!
                            nextValue = null
                            result
                        }

                        else -> throw NoSuchElementException()
                    }
                }
            }
        }
    }
}

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

