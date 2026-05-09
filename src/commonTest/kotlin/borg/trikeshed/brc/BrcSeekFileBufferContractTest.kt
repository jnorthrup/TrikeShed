package borg.trikeshed.brc

import borg.trikeshed.SeekFileBufferCommon
import borg.trikeshed.SeekHandle
import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.lib.ByteSeries
import kotlin.test.Test
import kotlin.test.assertEquals

class BrcSeekFileBufferContractTest {

    // 17a — open read-only reads byte at offset
    @Test
    fun `open read-only reads byte at offset`() {
        val data = byteArrayOf(10, 20, 30, 40, 50)
        val handle = ByteArraySeekHandle(data)
        val buf = SeekFileBufferCommon("test.bin", handle = handle)
        buf.open()

        assertEquals(10, buf.get(0L))
        assertEquals(30, buf.get(2L))
        assertEquals(50, buf.get(4L))

        buf.close()
    }

    // 17b — initial offset limits visible range
    @Test
    fun `initial offset limits visible range`() {
        val data = byteArrayOf(10, 20, 30, 40, 50, 60, 70)
        val handle = ByteArraySeekHandle(data)
        val buf = SeekFileBufferCommon("test.bin", initialOffset = 3L, handle = handle)
        buf.open()

        assertEquals(4, buf.size())  // 7 bytes total, offset 3 → visible range is 4
        assertEquals(40, buf.get(0L)) // first visible byte is data[3]
        assertEquals(50, buf.get(1L))
        assertEquals(60, buf.get(2L))
        assertEquals(70, buf.get(3L))

        buf.close()
    }
}

/** SPI test double: in-memory SeekHandle backed by a ByteArray. */
private class ByteArraySeekHandle(private val data: ByteArray) : SeekHandle {
    private var pos: Long = 0

    override fun open(filename: String, readOnly: Boolean): Long {
        pos = 0
        return 1 // non-negative = open
    }

    override fun close(handle: Long) {}

    override fun pread(handle: Long, dst: ByteRegion, fileOffset: Long): Int {
        val remaining = (data.size - fileOffset).coerceAtLeast(0).toInt()
        val toRead = remaining.coerceAtMost(dst.size)
        if (toRead <= 0) return -1
        for (i in 0 until toRead) {
            dst.put(i, data[(fileOffset + i).toInt()])
        }
        return toRead
    }

    override fun pwrite(handle: Long, src: ByteSeries, fileOffset: Long): Int = 0

    override fun size(handle: Long): Long = data.size.toLong()

    override fun read(handle: Long, dst: ByteRegion): Int {
        val remaining = (data.size - pos).coerceAtLeast(0).toInt()
        val toRead = remaining.coerceAtMost(dst.size)
        if (toRead <= 0) return -1
        for (i in 0 until toRead) {
            dst.put(i, data[(pos + i).toInt()])
        }
        pos += toRead
        return toRead
    }

    override fun write(handle: Long, src: ByteSeries): Int = 0

    override fun seek(handle: Long, position: Long): Long {
        pos = position.coerceIn(0, data.size.toLong())
        return pos
    }
}
