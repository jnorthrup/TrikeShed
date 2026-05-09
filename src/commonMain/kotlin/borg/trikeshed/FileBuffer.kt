package borg.trikeshed

import kotlin.coroutines.CoroutineContext

/**
 * An openable, closeable, mmap-style file buffer.
 *
 * CCEK: register platform implementation via [Key] in the coroutine context.
 * @deprecated Prefer resolving via `coroutineContext[FileBuffer.Key]` rather than direct construction.
 */
@Deprecated("Resolve via FileBuffer.Key in coroutine context")
expect class FileBuffer(
    filename: String,
    initialOffset: Long = 0,
    blkSize: Long = -1,
    readOnly: Boolean = true,
    closeChannelOnMap: Boolean = true,
) : LongSeries<Byte>, CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
    companion object Key : CoroutineContext.Key<FileBuffer>

    override val a: Long
    override val b: (Long) -> Byte
    val filename: String
    val initialOffset: Long
    val blkSize: Long
    val readOnly: Boolean
    val closeChannelOnMap: Boolean
    fun close()
    fun open()
    fun isOpen(): Boolean
    fun size(): Long
    fun get(index: Long): Byte
    fun put(index: Long, value: Byte)
}

fun <T> FileBuffer.use(block: (FileBuffer) -> T): T {
    open()
    try { return block(this) } finally { close() }
}

fun open(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true, closeChannelOnMap: Boolean = true): FileBuffer =
    FileBuffer(filename, initialOffset, blkSize, readOnly, closeChannelOnMap)

fun openFileBuffer(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true, closeChannelOnMap: Boolean = true): FileBuffer =
    open(filename, initialOffset, blkSize, readOnly, closeChannelOnMap)
