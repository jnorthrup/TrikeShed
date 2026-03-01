package borg.trikeshed.common

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
     * Each read goes into its corresponding buffer, advancing position.
     * Returns bytes read for each request (may be short on EOF).
     */
    fun readv(requests: List<Pair<Long, java.nio.ByteBuffer>>): IntArray
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
