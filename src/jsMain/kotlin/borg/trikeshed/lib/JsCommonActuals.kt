package borg.trikeshed.lib

import borg.trikeshed.lib.long.LongSeries
import borg.trikeshed.userspace.ByteRegion
import kotlin.random.Random
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.common.Files
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.SeekFileBufferCommon
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries



fun streamByteLines(bytes: ByteArray): Sequence<Join<Long, ByteArray>> = sequence {
    var offset = 0L
    var lineStart = 0L
    val line = ArrayList<Byte>()

    for (byte in bytes) {
        line += byte
        offset++
        if (byte == '\n'.code.toByte()) {
            yield(lineStart j line.toByteArray())
            line.clear()
            lineStart = offset
        }
    }

    if (line.isNotEmpty()) {
        yield(lineStart j line.toByteArray())
    }
}

fun mktemp(): String = jsMktemp()

fun rm(path: String): Boolean = jsRm(path)

fun mkdir(path: String): Boolean = jsMkdir(path)

fun readLinesSeq(path: String): Sequence<String> =
    Files.readAllLines(path).asSequence()

fun readLines(path: String): List<String> = Files.readAllLines(path)


class SeekFileBuffer(
    val filename: String,
    val initialOffset: Long = 0,
    val blkSize: Long = -1,
    val readOnly: Boolean = true,
) : LongSeries<Byte> {
   val delegate = SeekFileBufferCommon(filename, initialOffset, blkSize, readOnly)

    override val a: Long
        get() = delegate.a

    override val b: (Long) -> Byte
        get() = delegate.b

    fun close() {
        delegate.close()
    }

    fun open() {
        delegate.open()
    }

    fun isOpen(): Boolean = delegate.isOpen()

    fun size(): Long = delegate.size()

    fun get(index: Long): Byte = delegate.get(index)

    fun readv(requests: Series2<Long, ByteRegion>): IntArray = delegate.readv(requests)

    fun seek(pos: Long) {
        throw UnsupportedOperationException("seek not supported in JS")
    }

    fun put(index: Long, value: Byte) {
        throw UnsupportedOperationException("put not supported in JS")
    }
}
