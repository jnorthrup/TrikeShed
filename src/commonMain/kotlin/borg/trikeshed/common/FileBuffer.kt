
package borg.trikeshed.common

import borg.trikeshed.lib.debug
import borg.trikeshed.lib.logDebug


fun openFileBuffer(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true): FileBuffer=open(filename, initialOffset, blkSize, readOnly)

fun <T> FileBuffer.use(block: (FileBuffer) -> T) {
    open()
    try {
        block(this)
    } finally {
        close()
    }
}
fun open(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true): FileBuffer {
    logDebug { "pre-opening $filename" }
    return FileBuffer(filename, initialOffset, blkSize, readOnly).apply {
        logDebug { "this isOpen()=${isOpen()}" }
        open().debug { logDebug { "call(ed) open()" } }
        logDebug { "this isOpen()=${isOpen()}" }
    }
}
/**
 * an openable and closeable mmap file.
 *
 *  get has no side effects but put has undefined effects on size and sync
 */
expect class FileBuffer(
    filename: String,
    initialOffset: Long=0,
    /** blocksize or file-size if -1*/
    blkSize: Long=-1,
    readOnly: Boolean=true,
): LongSeries<Byte> {
    val filename: String
    val initialOffset: Long
    val blkSize: Long
    val readOnly: Boolean
    fun close()
    fun open() //post-init open
    fun isOpen(): Boolean
    fun size(): Long
    fun get(index: Long): Byte
    fun put(index: Long, value: Byte)
}