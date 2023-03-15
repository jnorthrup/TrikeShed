@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.locks.ReentrantLock

actual class IsamDataFile actual constructor(
    datafileFilename: String,
    metafileFilename: String,
    metafile: IsamMetaFileReader,
) : Usable, Cursor {
    actual val datafileFilename: String
    actual val metafile: IsamMetaFileReader

    val recordlen: Int
    val constraints: Series<RecordMeta> get() = metafile.constraints

    private lateinit var data: SeekableByteChannel

    var fileSize: Long = -1
    override fun toString(): String =
        "IsamDataFile(metafile=$metafile, recordlen=$recordlen, constraints=$constraints," +
                " datafileFilename='$datafileFilename', fileSize=$fileSize)"

    actual override fun open() {
        metafile.open()
        data = Files.newByteChannel(
            Paths.get(datafileFilename),
            READ,
            SPARSE
        )
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

    override val a: Int
    override val b: (Int) -> RowVec


    actual override fun close(): Unit = data.close()

    private val lock: ReentrantLock


    init {
        this.datafileFilename = datafileFilename
        this.metafile = metafile
        this.recordlen = metafile.recordlen
        this.a = fileSize.toInt() / recordlen
        this.lock = ReentrantLock()
        this.b = { row ->
            lock.lock()
            val buffer = ByteBuffer.allocate(recordlen)
            data.position(row * recordlen.toLong())
            data.read(buffer)
            lock.unlock()
            val array = buffer.position(0).array()

            constraints.size j { col ->
                val constraint = constraints[col]
                val s = array.sliceArray(constraint.begin until constraint.end)
                constraint.decoder(s)!! j { constraint }
            }
        }
    }

    actual companion object {
        actual fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>) {
            val metafilename = "$datafilename.meta"

            val meta0 = IsamMetaFileReader.write(metafilename, cursor.meta, varChars)

            //open RandomAccessDataFile

            val randomAccessFile = RandomAccessFile(datafilename, "rw")
            val data = randomAccessFile.channel

            //create row buffer
            meta0.debug {
                logDebug { "toIsam: " + it.map { it.toString() } }
            }

            val last = meta0.last()

            val rowLen = last.end
            val rowBuffer1 = ByteBuffer.allocate(rowLen)
            val rowBuffer = rowBuffer1.array()

            //write rows
            cursor.iterator().forEach { rowVec ->
                WireProto.writeToBuffer(rowVec, rowBuffer, meta0)
                rowBuffer1.position(0)
                data.write(rowBuffer1)
            }
            randomAccessFile.close()
            data.close()
        }

        actual suspend fun append(
            msf: MutableSharedFlow<RowVec>,
            datafilename: String,
            varChars: Map<String, Int>,
            transform: ((RowVec) -> RowVec)?
        ):Unit=  withContext(Dispatchers.IO) {
            val metafilename = "$datafilename.meta"

            // TODO("not assume we have to write this file for this call.  if it exists, verify it and use it")
           lateinit  var meta0: Series<RecordMeta>

            // open RandomAccessDataFile
            val data =
                Files.newOutputStream(Paths.get(datafilename), APPEND, WRITE, CREATE)


            var last: RecordMeta

            var rowLen = 0

            lateinit var rowBuffer: ByteArray
            var fibLog: FibonacciReporter? = null
            debug { fibLog = FibonacciReporter(size = null, noun = "appends") }
            var first = true
            // write rows
            msf.collect { rowVec1: RowVec ->
                val rowVec = transform?.let { it(rowVec1) } ?: rowVec1
                if (first) {
                    meta0 = IsamMetaFileReader.write(metafilename, rowVec.right.α { it() }, varChars)
                    last = meta0.last()
                    rowLen = last.end
                    rowBuffer = ByteArray(rowLen) { 0 }
                    debug { logDebug { "toIsam: " + meta0.toList() } }
                    first = false
                }

                WireProto.writeToBuffer(rowVec, rowBuffer, meta0)
                withContext(Dispatchers.IO) { data.write(rowBuffer) }
                debug { fibLog?.report()?.let { println(it) } }
            }
//            withContext(Dispatchers.IO) {
//                data.close()
//            }
        }
    }
}