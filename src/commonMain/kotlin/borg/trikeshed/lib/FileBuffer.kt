@file:Suppress("DEPRECATION")

package borg.trikeshed.lib

import borg.trikeshed.lib.long.LongSeries
import kotlin.coroutines.CoroutineContext

/**
 * An openable, closeable, mmap-style file buffer.
 *
 * @deprecated Use [SeekFileBufferCommon] routed through [borg.trikeshed.userspace.nio.channel.Channel].
 * FileBuffer's CoroutineContext.Element coupling is being removed —
 * all file I/O now goes through the unified UringFacade.
 */
@Deprecated("Use SeekFileBufferCommon via Channel/UringFacade")
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

@Deprecated("Use SeekFileBufferCommon via Channel/UringFacade")
fun <T> FileBuffer.use(block: (FileBuffer) -> T): T {
    open()
    try { return block(this) } finally { close() }
}

@Deprecated("Use SeekFileBufferCommon via Channel/UringFacade")
fun open(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true, closeChannelOnMap: Boolean = true): FileBuffer =
    FileBuffer(filename, initialOffset, blkSize, readOnly, closeChannelOnMap)

@Deprecated("Use SeekFileBufferCommon via Channel/UringFacade")
fun openFileBuffer(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true, closeChannelOnMap: Boolean = true): FileBuffer =
    open(filename, initialOffset, blkSize, readOnly, closeChannelOnMap)
