package borg.trikeshed.userspace.nio.spi

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.nio.ByteBuffer

class ByteRegion(
    val buffer: ByteBuffer,
    val start: Int = buffer.position(),
    val endExclusive: Int = buffer.limit(),
) {
    init {
        require(start >= 0) { "start must be non-negative" }
        require(endExclusive >= start) { "endExclusive must be >= start" }
        require(endExclusive <= buffer.limit()) { "endExclusive must be within buffer limit" }
    }

    val size: Int get() = endExclusive - start

    fun get(index: Int): Byte {
        require(index in 0 until size) { "index out of bounds: $index" }
        return buffer.get(start + index)
    }

    fun put(index: Int, value: Byte): ByteRegion {
        require(index in 0 until size) { "index out of bounds: $index" }
        buffer.put(start + index, value)
        return this
    }

    fun asByteBuffer(): ByteBuffer = buffer.slice(start, endExclusive)

    fun asByteSeries(): ByteSeries {
        val length = size
        return ByteSeries(length j { i: Int -> buffer.get(start + i) })
    }

    fun toByteArray(): ByteArray = ByteArray(size) { i -> buffer.get(start + i) }

    companion object {
        fun wrap(bytes: ByteArray): ByteRegion = ByteRegion(ByteBuffer.wrap(bytes))
        fun allocate(size: Int): ByteRegion = ByteRegion(ByteBuffer.allocate(size))
    }
}

fun ByteBuffer.asByteRegion(): ByteRegion = ByteRegion(this)
fun ByteBuffer.asByteSeries(): ByteSeries = asByteRegion().asByteSeries()
