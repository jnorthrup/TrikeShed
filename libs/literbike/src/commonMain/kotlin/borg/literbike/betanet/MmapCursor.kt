package borg.literbike.betanet

/**
 * MmapCursor - Memory-mapped cursor over data files.
 * Ported from literbike/src/betanet/mmap_cursor.rs.
 *
 * In Kotlin/JVM we use java.nio.MappedByteBuffer instead of raw mmap.
 */

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * ISAM header for mmap'd files.
 */
data class ISAMHeader(
    var magic: Long = 0L,
    var recordCount: Long = 0L,
    var recordSize: Long = 0L,
    var indexCount: Long = 0L,
    var dataOffset: Long = 0L,
    var reserved: LongArray = LongArray(3)
) {
    companion object {
        const val MAGIC: Long = -0x215241310526D982L // 0xDEADBEEF_CAFEBABE as signed
        const val SIZE: Int = 64 // 8 * 8 bytes
    }
}

/**
 * Raw pointer-based cursor over mmap'd data files.
 * Uses MappedByteBuffer for zero-allocation access.
 */
class MmapCursor(
    dataPath: String,
    indexPath: String
) : AutoCloseable {

    private val dataFile: RandomAccessFile
    private val indexFile: RandomAccessFile
    private val dataChannel: FileChannel
    private val indexChannel: FileChannel
    private val dataBuffer: ByteBuffer
    private val indexBuffer: ByteBuffer

    val dataFd: Int
    val indexFd: Int

    init {
        dataFile = RandomAccessFile(dataPath, "rw")
        indexFile = RandomAccessFile(indexPath, "rw")

        // Ensure minimum file sizes
        val minDataSize = ISAMHeader.SIZE.toLong()
        if (dataFile.length() < minDataSize) {
            dataFile.setLength(minDataSize)
        }
        val minIndexSize = 8L * 1024 // Min 8KB index
        if (indexFile.length() < minIndexSize) {
            indexFile.setLength(minIndexSize)
        }

        dataChannel = dataFile.channel
        indexChannel = indexFile.channel

        dataBuffer = dataChannel.map(FileChannel.MapMode.READ_WRITE, 0, dataFile.length())
        indexBuffer = indexChannel.map(FileChannel.MapMode.READ_WRITE, 0, indexFile.length())

        dataBuffer.order(ByteOrder.LITTLE_ENDIAN)
        indexBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // On JVM we can't get raw file descriptors easily; use -1 as placeholder
        dataFd = -1
        indexFd = -1
    }

    /** Initialize ISAM header in data file */
    fun initHeader(recordSize: Int): Result<Unit> {
        if (dataBuffer.capacity() < ISAMHeader.SIZE) {
            return Result.failure(Exception("Data file too small for ISAM header"))
        }
        dataBuffer.position(0)
        dataBuffer.putLong(ISAMHeader.MAGIC)
        dataBuffer.putLong(0) // recordCount
        dataBuffer.putLong(recordSize.toLong())
        dataBuffer.putLong(0) // indexCount
        dataBuffer.putLong(ISAMHeader.SIZE.toLong()) // dataOffset
        repeat(3) { dataBuffer.putLong(0) } // reserved
        dataBuffer.position(0)
        return Result.success(Unit)
    }

    /** Get ISAM header */
    fun header(): ISAMHeader {
        dataBuffer.position(0)
        return ISAMHeader(
            magic = dataBuffer.long,
            recordCount = dataBuffer.long,
            recordSize = dataBuffer.long,
            indexCount = dataBuffer.long,
            dataOffset = dataBuffer.long,
            reserved = LongArray(3) { dataBuffer.long }
        )
    }

    /** Seek to record by index (zero-based). Returns ByteBuffer slice or null */
    fun seek(index: Long): ByteBuffer? {
        val header = header()
        if (index >= header.recordCount) return null
        val offset = header.dataOffset + (index * header.recordSize)
        val slice = dataBuffer.duplicate()
        slice.position(offset.toInt())
        slice.limit((offset + header.recordSize).toInt())
        return slice.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    /** Append new record. Returns index of appended record */
    fun append(data: ByteArray): Result<Long> {
        val header = header()
        if (data.size.toLong() != header.recordSize) {
            return Result.failure(Exception("Record size mismatch"))
        }
        val newIndex = header.recordCount
        val recordOffset = header.dataOffset + (newIndex * header.recordSize)
        if (recordOffset + header.recordSize > dataBuffer.capacity()) {
            return Result.failure(Exception("Data file full"))
        }
        dataBuffer.position(recordOffset.toInt())
        dataBuffer.put(data)
        // Update header
        dataBuffer.position(8) // recordCount offset
        dataBuffer.putLong(newIndex + 1)
        dataBuffer.position(0)
        return Result.success(newIndex)
    }

    /** Update record in place (leverages idempotent tuples) */
    fun update(index: Long, data: ByteArray): Result<Unit> {
        val buf = seek(index) ?: return Result.failure(Exception("Index out of bounds"))
        if (data.size != buf.remaining()) {
            return Result.failure(Exception("Record size mismatch"))
        }
        buf.put(data)
        return Result.success(Unit)
    }

    /** Get record as ByteArray */
    fun get(index: Long): ByteArray? {
        val buf = seek(index) ?: return null
        val arr = ByteArray(buf.remaining())
        buf.get(arr)
        return arr
    }

    /** Scan all records sequentially */
    fun scan(recordSize: Int): List<ByteArray> {
        val header = header()
        val result = mutableListOf<ByteArray>()
        for (i in 0 until header.recordCount) {
            val buf = seek(i) ?: break
            val arr = ByteArray(recordSize)
            buf.get(arr)
            result.add(arr)
        }
        return result
    }

    /** Force sync to disk */
    fun sync(): Result<Unit> {
        return runCatching {
            dataBuffer.force()
            indexBuffer.force()
            dataChannel.force(true)
            indexChannel.force(true)
        }
    }

    /** Compact data file */
    fun compact(): Result<Unit> {
        val header = header()
        val recordSize = header.recordSize.toInt()
        var writeIndex = 0L

        val validRecords = mutableListOf<ByteArray>()
        for (readIndex in 0 until header.recordCount) {
            val record = get(readIndex)
            if (record != null && isRecordValid(record)) {
                validRecords.add(record)
            }
        }

        // Rewrite valid records
        dataBuffer.position(header.dataOffset.toInt())
        for (record in validRecords) {
            dataBuffer.put(record)
        }
        // Update record count
        dataBuffer.position(8)
        dataBuffer.putLong(validRecords.size.toLong())
        dataBuffer.position(0)
        return Result.success(Unit)
    }

    private fun isRecordValid(record: ByteArray): Boolean {
        // Check if first 8 bytes are non-zero
        if (record.size < 8) return false
        var firstU64 = 0L
        for (i in 0 until 8) {
            firstU64 = (firstU64 shl 8) or (record[i].toLong() and 0xFF)
        }
        return firstU64 != 0L
    }

    /** Get current record count */
    fun len(): Long = header().recordCount

    /** Check if cursor is empty */
    fun isEmpty(): Boolean = len() == 0L

    override fun close() {
        sync()
        dataChannel.close()
        indexChannel.close()
        dataFile.close()
        indexFile.close()
    }
}
