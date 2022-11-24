package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Cursor
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files

actual class IsamDataFile(
    val datafileFilename: String,
    metafileFilename: String = "$datafileFilename.meta",
    val metafile: IsamMetaFileReader = IsamMetaFileReader(metafileFilename),
) : Cursor {
    val recordlen = metafile.recordlen
    val constraints = metafile.constraints

    private lateinit var data: SeekableByteChannel

    var fileSize: Long = -1
    override fun toString(): String =
        "IsamDataFile(metafile=$metafile, recordlen=$recordlen, constraints=$constraints," +
                " datafileFilename='$datafileFilename', fileSize=$fileSize)"

    actual fun open() {
        data = Files.newByteChannel(java.nio.file.Paths.get(datafileFilename))
        fileSize = data.size()

        // report on record alignment of the file
        val alignment = fileSize % recordlen
        if (alignment != 0L) {
            println("WARN: file $datafileFilename is not aligned to recordlen $recordlen")
        } else
            println("DEBUG: file $datafileFilename is aligned to recordlen $recordlen")

        // mention record counts and percentages of each field type by record byte occupancy
        val fieldCounts = mutableMapOf<IOMemento, Int>()
        val fieldOccupancy = mutableMapOf<IOMemento, Int>()
        constraints.forEach { constraint ->
            val count = fieldCounts.getOrPut(constraint.type) { 0 }
            fieldCounts[constraint.type] = count + 1
            val occupancy = fieldOccupancy.getOrPut(constraint.type) { 0 }
            fieldOccupancy[constraint.type] = occupancy + constraint.end - constraint.begin
        }
        println("DEBUG: file $datafileFilename has ${fileSize / recordlen} records")
        fieldCounts.forEach { (type, count) ->
            val occupancy = fieldOccupancy[type]!!
            println("DEBUG: file $datafileFilename has $count fields of type $type occupying $occupancy bytes")
        }
    }

    override val a: Int = fileSize.toInt() / recordlen
    actual fun close() {
        data.close()
    }

    private val lock = java.util.concurrent.locks.ReentrantLock()

    override val b: (Int) -> Join<Int, (Int) -> Join<*, () -> RecordMeta>> = { row ->
        lock.lock()
        val buffer = ByteBuffer.allocate(recordlen)
        data.position(row * recordlen.toLong())
        data.read(buffer)
        lock.unlock()
        val array = buffer.position(0).array()

        constraints.size j { col ->
            val constraint = constraints[col]
            val s = array.sliceArray(constraint.begin until constraint.end)
            constraint.decoder(s) j { constraint }
        }
    }
}
