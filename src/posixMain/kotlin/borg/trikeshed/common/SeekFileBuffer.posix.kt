package borg.trikeshed.common

import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.Series2

actual class SeekFileBuffer actual constructor(
    filename: String,
    initialOffset: Long,
    blkSize: Long,
    readOnly: Boolean
) : LongSeries<Byte> {
    actual override val a: Long
        get() = TODO("Not yet implemented")
    actual override val b: (Long) -> Byte
        get() = TODO("Not yet implemented")
    actual val filename: String
        get() = TODO("Not yet implemented")
    actual val initialOffset: Long
        get() = TODO("Not yet implemented")
    actual val blkSize: Long
        get() = TODO("Not yet implemented")
    actual val readOnly: Boolean
        get() = TODO("Not yet implemented")

    actual fun close() {
    }

    actual fun open() {
    }

    actual fun isOpen(): Boolean {
        TODO("Not yet implemented")
    }

    actual fun size(): Long {
        TODO("Not yet implemented")
    }

    actual fun get(index: Long): Byte {
        TODO("Not yet implemented")
    }

    actual fun put(index: Long, value: Byte) {
    }

    actual fun seek(pos: Long) {
    }

    actual fun readv(requests: Series2<Long, ByteSeries>): IntArray {
        TODO("Not yet implemented")
    }
}
