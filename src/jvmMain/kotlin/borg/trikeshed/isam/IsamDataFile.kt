@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.isam

import borg.trikeshed.lib.Usable
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.locks.ReentrantLock

/**
 * ISAM Data File — actual JVM implementation of Cursor.
 * Uses interface-implementation pattern: actual class with explicit interface members.
 */
actual class IsamDataFile actual constructor(
    datafileFilename: String,
    metafileFilename: String,
    metafile: IsamMetaFileReader,
    @Suppress("UNUSED_PARAMETER") fileOps: FileOperations,
) : Usable, Cursor {

    actual val datafileFilename = datafileFilename
    actual val metafile by lazy {
        metafile.open()
        metafile
    }

    val data: SeekableByteChannel by lazy {
        Files.newByteChannel(
            Paths.get(datafileFilename),
            READ
        )
    }

    val recordlen: Int by lazy { metafile.recordlen }
    val fileSize: Long get() = data.size()
    actual override val a: Int by lazy { fileSize.toInt() / recordlen }

    val constraints: Series<RecordMeta> get() = metafile.constraints

    override fun toString(): String =
        "IsamDataFile(metafile=$metafile, recordlen=$recordlen, constraints=$constraints," +
                " datafileFilename='$datafileFilename', fileSize=$fileSize)"

    // Cursor interface: a = row count, b: (Int) -> RowVec
    actual override val a: Int by lazy { fileSize.toInt() / recordlen }

    actual override val b: (Int) -> RowVec = { row ->
        lock.lock()
        val buffer = ByteBuffer.allocate(recordlen)
        data.position(row * recordlen.toLong())
        data.read(buffer)
        lock.unlock()
        val array = buffer.position(0).array()

        // RowVec = Series2<Any?, ColumnMeta↻>
        // Join size (col count) with index -> RowVec
        constraints.size j { col: Int ->
            val constraint = constraints[col]
            val s = array.sliceArray(constraint.begin until constraint.end)
            // Return RowVec = Series2<Any?, ColumnMeta↻>
            // Value: decoded cell, Meta: supplier of ColumnMeta
            constraint.decoder(s)!! j { constraint }
        }
    }

    val lock: ReentrantLock = ReentrantLock()

    actual override fun open() {
        // report on record alignment of the file
        val alignment = fileSize % recordlen
        if (alignment != 0L) {
            println("WARN: file $datafileFilename is not aligned to recordlen $recordlen")
        } else
            println("DEBUG: file $datafileFilename is aligned to recordlen $recordlen")

        val fieldCounts: Map<IOMemento, Pair<Int, Int>> = constraints.view.groupBy { it.type }
            .mapValues { (_, v) -> v.size to v.sumOf { it.end - it.begin } }

        val ySize = fileSize / recordlen
        println("DEBUG: file $datafileFilename has $ySize records")
        fieldCounts.forEach { (type, pair) ->
            val (count, occupancy) = pair
            val ocu2 = occupancy.toLong()
            val unitSize = ocu2.humanReadableByteCountIEC
            val collectiveSize = (ySize.toLong() * ocu2).humanReadableByteCountIEC
            println("DEBUG: file $datafileFilename has $count fields of type $type occupying $ocu2 ($collectiveSize total)")
        }
    }

    val lock: ReentrantLock = ReentrantLock()

    actual override fun close() {
        lock.lock()
        try { data.close() } finally { lock.unlock() }
    }
}