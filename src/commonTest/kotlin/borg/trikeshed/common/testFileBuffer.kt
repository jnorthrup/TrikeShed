package borg.trikeshed.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * filebuffer looks like
 * ```
 * /**
 *  * an openable and closeable mmap file.
 *  *
 *  *  get has no side effects but put has undefined effects on size and sync
 *  */
 * expect class FileBuffer(
 *     filename: String,
 *     initialOffset: Long=0,
 *     /** blocksize or file-size if -1*/
 *     blkSize: Long=-1,
 *     readOnly: Boolean=true,
 * ): LongSeries<Byte> {
 *     val filename: String
 *     val initialOffset: Long
 *     val blkSize: Long
 *     val readOnly: Boolean
 *     fun close()
 *     fun open() //post-init open
 *     fun isOpen(): Boolean
 *     fun size(): Long
 *     fun get(index: Long): Byte
 *     fun put(index: Long, value: Byte)
 * }
 * fun openFileBuffer(filename: String, initialOffset: Long = 0, blkSize: Long = -1, readOnly: Boolean = true): FileBuffer=open(filename, initialOffset, blkSize, readOnly)
 * ```
 */
class TestFileBuffer {
    val refFile = "src/commonTest/resources/big.json"


    /**open the file with readallbytes and compare to the filebuffer contents in random segments*/
    @Test
    fun testFileBuffer() {
        val fileBytes = Files.readAllBytes((refFile))
        //now we know the bytecount of the file

        val fileBuffer = openFileBuffer(refFile).use { fbuf ->
            //treat the LongSeries like mmap buffer, and verify all the bytes are the same
            for (fileByte in fileBytes.indices) assertEquals(fileBytes[fileByte], fbuf[fileByte.toLong()])
        }
    }
}
