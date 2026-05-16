package borg.trikeshed.userspace.nio.file

import borg.trikeshed.lib.Join
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class FilesTest {
    @Test
    fun testStreamLinesTiny() {
        //write a file with 20 lines average of 2-75 bytes each line
        //stream the file and check the line contents
        val lines = (0 until 20).map { (0 until (2..75).random()).map { ('a'..'z').random() }.joinToString("") }
        assert(lines.size == 20)
        val tdir: Path = Files.createTempDirectory(Paths.get("/tmp"), "test")
        assert(Files.exists(tdir))
        val resolve = tdir.resolve("test.txt")
        Files.write(resolve, lines)
        val stream = Files.lines(resolve.toString())
        val streamLines = stream.toList()
        assert(streamLines.size == 20)
        assertContentEquals(streamLines.map { it.trim() }, lines.map { it.trim() })

        //test the seek with FileChannel on all fragment offsets
        val fragments = Files.fragments(resolve.toString()).toList()
        assertTrue(fragments.size >= 20, "expected at least 20 fragments, got ${fragments.size}")

        val raf = FileChannel.open(resolve)
        for ((pos, bytes) in fragments) {
            raf.position(pos)
            val buf = ByteBuffer.allocate(bytes.size)
            raf.read(buf)
            assert(buf.array().contentEquals(bytes))
        }
    }
}
