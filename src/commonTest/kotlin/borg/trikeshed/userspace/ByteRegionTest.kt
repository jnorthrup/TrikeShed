package borg.trikeshed.userspace

import borg.trikeshed.lib.asString
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

class ByteRegionTest {
    @Test
    fun byteRegionProjectsCurrentBufferWindowAsByteSeries() {
        val buffer = ByteBuffer.wrap("hello".encodeToByteArray())
        buffer.position(1)
        buffer.limit(4)

        val region = ByteRegion(buffer)

        assertEquals("ell", region.asByteSeries().asString())
    }

    @Test
    fun byteRegionMutationsWriteThroughToBackingBuffer() {
        val region = ByteRegion.wrap("hello".encodeToByteArray())

        region.put(1, 'a'.code.toByte())

        assertEquals("hallo", region.asByteSeries().asString())
    }
}
