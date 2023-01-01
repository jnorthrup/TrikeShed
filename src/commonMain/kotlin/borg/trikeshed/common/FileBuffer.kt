import borg.trikeshed.common.LongSeries


/**
 * an openable and closeable mmap file.
 *
 *  get has no side effects but put has undefined effects on size and sync
 */
expect class FileBuffer(
    filename: String, initialOffset: Long,
    /** blocksize or file-size if -1*/
    blkSize: Long,
    readOnly: Boolean,
): LongSeries<Byte > {
    val filename: String
    val initialOffset: Long
    val blkSize: Long
    val readOnly: Boolean
    fun close()
    fun open() //post-init open
    fun isOpen(): Boolean
    fun size(): Long
    fun get(index: Long): Byte
    fun put(index: Long, value: Byte) //undefined effects on size and sync
}

expect fun openFileBuffer(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true): FileBuffer