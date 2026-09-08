package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.Pipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UringPipeTest {
    @Test
    fun pipe_open_preserves_partial_fill_drain_backpressure_and_eof() {
        val pipe = Pipe.open()
        val source = pipe.source()
        val sink = pipe.sink()
        try {
            assertFalse(source.isBlocking())
            assertFalse(sink.isBlocking())
            assertEquals(0, source.read(ByteBuffer(1)), "empty open pipe is temporarily unavailable")
            val bytes = ByteArray(65537) { (it % 251).toByte() }
            val input = ByteBuffer(bytes)
            assertEquals(65536, sink.write(input), "bounded pipe permits a partial write")
            assertEquals(65536, input.position())
            assertEquals(0, sink.write(input), "full pipe applies backpressure")
            val prefix = ByteBuffer(3)
            assertEquals(3, source.read(prefix))
            for (i in 0 until 3) assertEquals(bytes[i], prefix.get(i))
            assertEquals(1, sink.write(input), "drained capacity is available for a subsequent fill")
            assertEquals(65537, input.position())
            sink.close()
            val backing = ByteArray(65545) { -1 }
            val output = ByteBuffer(backing).slice(2, 65542).position(1)
            assertEquals(65534, source.read(output), "closing the sink retains already written bytes")
            assertEquals(65535, output.position())
            assertEquals((-1).toByte(), backing[2])
            for (i in 3 until bytes.size) assertEquals(bytes[i], backing[i])
            assertEquals((-1).toByte(), backing[65537])
            assertEquals(-1, source.read(output))
            assertEquals(0, source.read(ByteBuffer(0)), "empty destination does not report EOF")
            assertFailsWith<IllegalArgumentException> { source.configureBlocking(true) }
        } finally {
            sink.close()
            source.close()
        }
        assertFalse(source.isOpen())
        assertFalse(sink.isOpen())
    }

    @Test
    fun closing_source_reports_broken_pipe_without_consuming_input() {
        val pipe = Pipe.open()
        pipe.source().close()
        val input = ByteBuffer(byteArrayOf(7))
        try {
            assertFailsWith<IOException> { pipe.sink().write(input) }
            assertEquals(0, input.position())
            assertTrue(pipe.sink().isOpen())
        } finally { pipe.sink().close() }
    }
}
