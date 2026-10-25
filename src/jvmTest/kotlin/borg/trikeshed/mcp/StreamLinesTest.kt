package borg.trikeshed.mcp

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The framing is commonMain and is pinned by ChunkedLineSourceTest on every target; these pin what is JVM about the stream adapters. */
class StreamLinesTest {
    @Test
    fun anInputStreamIsReadAndFramedLikeAnyOtherByteSource() = runBlocking {
        val s = InputStreamLineSource(ByteArrayInputStream("a\r\nb\nlast".encodeToByteArray()), 2)
        val lines = buildList {
            while (true) {
                when (val r = s.read(100)) {
                    is LineRead.Line -> add(r.bytes.decodeToString())
                    LineRead.TooLong -> add("<too-long>")
                    LineRead.Eof -> break
                }
            }
        }
        assertEquals(listOf("a", "b", "last"), lines)
    }

    private class Recording : OutputStream() {
        val writes = mutableListOf<ByteArray>()
        var flushes = 0
        override fun write(b: Int) = error("the sink must write whole frames")
        override fun write(b: ByteArray, off: Int, len: Int) { writes += b.copyOfRange(off, off + len) }
        override fun flush() { flushes++ }
    }

    @Test
    fun theSinkWritesOneFramePerReplyInASingleWriteAndFlushes() = runBlocking {
        val out = Recording()
        val sink = OutputStreamLineSink(out)
        sink.write("""{"a":1}""".encodeToByteArray())
        sink.write("""{"b":2}""".encodeToByteArray())
        assertEquals(listOf("{\"a\":1}\n", "{\"b\":2}\n"), out.writes.map { it.decodeToString() })
        assertEquals(2, out.flushes)
    }

    @Test
    fun theSinkRefusesAReplyThatWouldBecomeTwoLinesBeforeAnythingIsWritten() = runBlocking {
        val out = Recording()
        val sink = OutputStreamLineSink(out)
        assertFailsWith<IllegalStateException> { sink.write("a\nb".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { sink.write("a\rb".encodeToByteArray()) }
        assertTrue(out.writes.isEmpty(), "nothing partial reached the stream")
    }
}
