package borg.trikeshed.mcp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Hands out [bytes] at most [chunk] at a time, then reports the end: a pipe whose writer wrote in small pieces. */
private class Pipe(private val bytes: ByteArray, private val chunk: Int) {
    var reads = 0
        private set
    private var at = 0

    suspend fun read(buffer: ByteArray): Int {
        reads++
        if (at >= bytes.size) return -1
        val n = minOf(chunk, buffer.size, bytes.size - at)
        bytes.copyInto(buffer, 0, at, at + n)
        at += n
        return n
    }
}

/** The framing authority, with no platform under it: the same assertions hold over a JVM stream, a native descriptor and this pipe. */
class ChunkedLineSourceTest {
    private fun source(text: String, buffer: Int = 8, pipe: Pipe = Pipe(text.encodeToByteArray(), buffer)) =
        ChunkedLineSource({ pipe.read(it) }, buffer)

    private suspend fun all(s: LineSource, max: Int): List<String> = buildList {
        while (true) {
            when (val r = s.read(max)) {
                is LineRead.Line -> add(r.bytes.decodeToString())
                LineRead.TooLong -> add("<too-long>")
                LineRead.Eof -> break
            }
        }
    }

    @Test
    fun linesAreFramedByLfOrCrlfAndAFinalUnterminatedLineStillCounts() = runTest {
        assertEquals(listOf("a", "b", "", "last"), all(source("a\nb\r\n\nlast"), 100))
        assertEquals(emptyList(), all(source(""), 100))
        assertEquals(listOf(""), all(source("\n"), 100))
        assertEquals(listOf("only"), all(source("only"), 100))
        assertEquals(listOf("x", "y"), all(source("x\ny\n"), 100), "a trailing terminator does not invent an empty line")
    }

    @Test
    fun framingDoesNotDependOnBufferBoundaries() = runTest {
        val text = "hello world\nfoo\r\n\nline four is longer than the buffer\n"
        for (buffer in listOf(1, 2, 3, 5, 8, 4_096)) {
            assertEquals(listOf("hello world", "foo", "", "line four is longer than the buffer"), all(source(text, buffer), 1_000), "buffer=$buffer")
        }
    }

    @Test
    fun framingDoesNotDependOnHowTheWriterChunkedItsWrites() = runTest {
        val text = "{\"id\":1}\n{\"id\":2}\r\n\nlast"
        for (chunk in listOf(1, 2, 3, 7, 4_096)) {
            assertEquals(listOf("{\"id\":1}", "{\"id\":2}", "", "last"), all(source(text, 64, Pipe(text.encodeToByteArray(), chunk)), 100), "chunk=$chunk")
        }
    }

    @Test
    fun multiByteCharactersSplitAcrossReadsDecodeIdentically() = runTest {
        val text = "é€😀 ok\n日本語\n"
        for (buffer in 1..7) assertEquals(listOf("é€😀 ok", "日本語"), all(source(text, buffer), 1_000), "buffer=$buffer")
    }

    @Test
    fun theLimitCountsContentNotTheCarriageReturnAndTooLongLinesResynchronise() = runTest {
        assertEquals(listOf("12345", "<too-long>", "<too-long>", "ok"), all(source("12345\n123456\n1234567890123\nok\n"), 5))
        assertEquals(listOf("12345"), all(source("12345\r\n"), 5), "CR is framing, not content")
        assertEquals(listOf("<too-long>"), all(source("123456\r\n"), 5))
        assertEquals(listOf("<too-long>"), all(source("1234567890"), 5), "an over-long final line without a terminator is still refused")
        assertEquals(listOf("ab", "<too-long>", "cd"), all(source("ab\n123456\ncd", 3), 5))
    }

    @Test
    fun anEnormousLineIsSkippedWithoutBeingHeldAndTheStreamResynchronises() = runTest {
        var left = 50_000_000L
        val tail = "\nnext\n".encodeToByteArray()
        var tailAt = 0
        val generated = ChunkedLineSource({ b ->
            when {
                left > 0 -> { val n = minOf(b.size.toLong(), left).toInt(); b.fill('x'.code.toByte(), 0, n); left -= n; n }
                tailAt < tail.size -> { val n = minOf(b.size, tail.size - tailAt); tail.copyInto(b, 0, tailAt, tailAt + n); tailAt += n; n }
                else -> -1
            }
        }, 8_192)
        assertEquals(listOf("<too-long>", "next"), all(generated, 1_024))
    }

    @Test
    fun readingPastTheEndKeepsReportingTheEndWithoutAskingTheSourceAgain() = runTest {
        val pipe = Pipe("one\n".encodeToByteArray(), 64)
        val s = source("", 64, pipe)
        assertTrue(s.read(10) is LineRead.Line)
        assertTrue(s.read(10) === LineRead.Eof)
        val readsAtEnd = pipe.reads
        assertTrue(s.read(10) === LineRead.Eof)
        assertEquals(readsAtEnd, pipe.reads, "an exhausted source is not consulted again")
    }

    @Test
    fun aReadThatProducedNothingIsAskedAgainAndIsNotTheEnd() = runTest {
        var calls = 0
        val data = "late\n".encodeToByteArray()
        val s = ChunkedLineSource({ b ->
            when (calls++) {
                0, 1 -> 0
                2 -> { data.copyInto(b); data.size }
                else -> -1
            }
        }, 16)
        assertEquals(listOf("late"), all(s, 10))
    }

    @Test
    fun aSourceThatReturnsMoreThanItWasGivenRoomForIsABugNotData() = runTest {
        val s = ChunkedLineSource({ b -> b.size + 1 }, 4)
        assertFailsWith<IllegalStateException> { s.read(10) }
    }

    @Test
    fun theSinkWritesOneFramePerReplyInASingleCall() = runTest {
        val frames = mutableListOf<ByteArray>()
        val sink = FramedLineSink { frames += it }
        sink.write("""{"a":1}""".encodeToByteArray())
        sink.write("""{"b":2}""".encodeToByteArray())
        assertEquals(listOf("{\"a\":1}\n", "{\"b\":2}\n"), frames.map { it.decodeToString() })
    }

    @Test
    fun theSinkRefusesAReplyThatWouldBecomeTwoLines() = runTest {
        val frames = mutableListOf<ByteArray>()
        val sink = FramedLineSink { frames += it }
        assertFailsWith<IllegalStateException> { sink.write("a\nb".encodeToByteArray()) }
        assertFailsWith<IllegalStateException> { sink.write("a\rb".encodeToByteArray()) }
        assertTrue(frames.isEmpty(), "nothing partial reached the stream")
    }
}
