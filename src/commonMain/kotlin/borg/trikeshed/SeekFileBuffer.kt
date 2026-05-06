package borg.trikeshed

import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.ByteRegion

/** An openable file buffer with seek semantics */
expect class SeekFileBuffer(
    filename: String,
    initialOffset: Long = 0,
    blkSize: Long = -1,
    readOnly: Boolean = true,
) : LongSeries<Byte> {
    override val a: Long
    override val b: (Long) -> Byte
    val filename: String
    val initialOffset: Long
    val blkSize: Long
    val readOnly: Boolean
    fun close()
    fun open()
    fun isOpen(): Boolean
    fun size(): Long
    fun get(index: Long): Byte
    fun put(index: Long, value: Byte)
    /** Seek to position — invalidates window buffer. */
    fun seek(pos: Long)
    /**
     * Batch read scattered offsets — sorted to minimize seeks (elevator).
     * Each read goes into its corresponding buffer window.
     * Returns bytes read for each request (may be short on EOF).
     */
    fun readv(requests: Series2<Long, ByteRegion>): IntArray
}

/** Open and use a SeekFileBuffer */
fun <T> SeekFileBuffer.use(block: (SeekFileBuffer) -> T): T {
    open()
    try {
        return block(this)
    } finally {
        close()
    }
}

/** Convenience overload: `readv(0L j region0, 4096L j region1, ...)` */
fun SeekFileBuffer.readv(vararg requests: Join<Long, ByteRegion>): IntArray =
    readv(requests.size j requests::get)
