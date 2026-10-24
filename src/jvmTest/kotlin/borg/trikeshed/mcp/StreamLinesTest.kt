package borg.trikeshed.mcp

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamLinesTest {
    private fun source(bytes: ByteArray, buffer: Int = 8) = InputStreamLineSource(ByteArrayInputStream(bytes), buffer)
    private fun source(text: String, buffer: Int = 8) = source(text.encodeToByteArray(), buffer)

    private fun all(s: InputStreamLineSource, max: Int): List<String> = runBlocking {
        buildList {
            while (true) {
                when (val r = s.read(max)) {
                    is LineRead.Line -> add(r.bytes.decodeToString())
                    LineRead.TooLong -> add("<too-long>")
                    LineRead.Eof -> break
                }
            }
        }
    }

    @Test
    fun linesAreFramedByLfOrCrlfAndAFinalUnterminatedLineStillCounts() {
        assertEquals(listOf("a", "b", "", "last"), all(source("a\nb\r\n\nlast"), 100))
        assertEquals(emptyList(), all(source(""), 100))
        assertEquals(listOf(""), all(source("\n"), 100))
        assertEquals(listOf("only"), all(source("only"), 100))
        assertEquals(listOf("x", "y"), all(source("x\ny\n"), 100), "a trailing terminator does not invent an empty line")
    }

    @Test
    fun framingDoesNotDependOnBufferBoundaries() {
        val text = "hello world\nfoo\r\n\nline four is longer than the buffer\n"
        for (buffer in listOf(1, 2, 3, 5, 8, 4_096)) {
            assertEquals(listOf("hello world", "foo", "", "line four is longer than the buffer"), all(source(text, buffer), 1_000), "buffer=$buffer")
        }
    }

    @Test
    fun multiByteCharactersSplitAcrossReadsDecodeIdentically() {
        val text = "é€😀 ok\n日本語\n"
        for (buffer in 1..7) assertEquals(listOf("é€😀 ok", "日本語"), all(source(text, buffer), 1_000), "buffer=$buffer")
    }

    @Test
    fun theLimitCountsContentNotTheCarriageReturnAndTooLongLinesResynchronise() {
        assertEquals(listOf("12345", "<too-long>", "<too-long>", "ok"), all(source("12345\n123456\n1234567890123\nok\n"), 5))
        assertEquals(listOf("12345"), all(source("12345\r\n"), 5), "CR is framing, not content")
        assertEquals(listOf("<too-long>"), all(source("123456\r\n"), 5))
        assertEquals(listOf("<too-long>"), all(source("1234567890"), 5), "an over-long final line without a terminator is still refused")
        assertEquals(listOf("ab", "<too-long>", "cd"), all(source("ab\n123456\ncd", 3), 5))
    }

    @Test
    fun anEnormousLineIsSkippedWithoutBeingHeldAndTheStreamResynchronises() {
        val generated = object : InputStream() {
            var left = 50_000_000L
            val tail = "\nnext\n".encodeToByteArray()
            var tailAt = 0
            override fun read(): Int = if (left > 0) { left--; 'x'.code } else if (tailAt < tail.size) tail[tailAt++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (left > 0) { val n = minOf(len.toLong(), left).toInt(); b.fill('x'.code.toByte(), off, off + n); left -= n; return n }
                if (tailAt >= tail.size) return -1
                val n = minOf(len, tail.size - tailAt); tail.copyInto(b, off, tailAt, tailAt + n); tailAt += n; return n
            }
        }
        assertEquals(listOf("<too-long>", "next"), all(InputStreamLineSource(generated, 8_192), 1_024))
    }

    @Test
    fun readingPastTheEndKeepsReportingTheEnd() = runBlocking {
        val s = source("one\n")
        assertTrue(s.read(10) is LineRead.Line)
        assertTrue(s.read(10) === LineRead.Eof)
        assertTrue(s.read(10) === LineRead.Eof)
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
    fun theSinkRefusesAReplyThatWouldBecomeTwoLines() = runBlocking {
        val out = Recording()
        val sink = OutputStreamLineSink(out)
        assertFailsWith<IllegalStateException> { sink.write("a\nb".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { sink.write("a\rb".encodeToByteArray()) }
        assertTrue(out.writes.isEmpty(), "nothing partial reached the stream")
    }
}
