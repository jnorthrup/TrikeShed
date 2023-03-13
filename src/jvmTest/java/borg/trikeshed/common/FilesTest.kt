package borg.trikeshed.common

import borg.trikeshed.lib.first
import borg.trikeshed.lib.second
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals


class FilesTest {
    @Test
    fun testStreamLinesTiny() {
        //write a file with 20 lines average of 2-75 bytes each line
        //stream the file and check the line contents
        val lines = (0 until 20).map { (0 until (2..75).random()).map { ('a'..'z').random() }.joinToString("") }
        assert(lines.size == 20)
        val tdir = createTempDirectory()
        assert(java.nio.file.Files.exists(tdir))
        val resolve = tdir.resolve("test.txt")
        java.nio.file.Files.write(resolve, lines)
        val stream = Files.streamLines(resolve.toString())
        val streamLines = stream.toList()
        assert(streamLines.size == 20)
        assertContentEquals(streamLines.map { it.second.decodeToString().trim() }, lines.map { it.trim() })

        //test the seek with RandomAccess file on all strings
        val raf = java.io.RandomAccessFile(resolve.toString(), "r")
        for (i in 0 until 20) {
            val (pos, bytes) = streamLines[i]
            raf.seek(pos)
            val read = ByteArray(bytes.size)
            raf.read(read)
            assert(read.contentEquals(bytes))
        }
    }
}