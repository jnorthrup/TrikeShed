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


    actual override val b: (Int) -> RowVec = { row ->
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
            println("DEBUG: file $datafileFilename has $count fields of type $type occupying $unitSize or total of $collectiveSize   (${occupancy * 100 / recordlen}%)")
        }
    }

    actual override fun close(): Unit = data.close()


    actual companion object {
        actual fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>, fileOps: FileOperations) {
            val metafilename = "$datafilename.meta"

            //turn off debug logging in here
//            fun logDebug(f: (String) -> Unit): Unit = Unit

            val meta0 = IsamMetaFileReader.write(metafilename, cursor.meta, varChars, fileOps)

            //open RandomAccessDataFile

            val randomAccessFile = RandomAccessFile(datafilename, "rw")
            val data = randomAccessFile.channel

            //create row buffer
            meta0.debug { logDebug { "toIsam: " + it.view.map { it.toString() } } }


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

        actual fun append(
            msf: Iterable<RowVec>,
            datafilename: String,
            varChars: Map<String, Int>,
            transform: ((RowVec) -> RowVec)?,
            fileOps: FileOperations,
        ): Unit {
            val metafilename = "$datafilename.meta"

            lateinit var meta0: Series<RecordMeta>
            var rowLen = 0
            lateinit var rowBuffer: ByteArray
            var first = true

            if (fileOps.exists(metafilename)) {
                meta0 = IsamMetaFileReader(metafilename, fileOps).constraints
                rowLen = meta0.last().end
                rowBuffer = ByteArray(rowLen) { 0 }
                first = false

                if (fileOps.exists(datafilename)) {
                    val fileSize = Files.size(Paths.get(datafilename))
                    val alignment = fileSize % rowLen
                    if (alignment != 0L) {
                        println("WARN: file $datafilename is not aligned to recordlen $rowLen")
                    } else {
                        println("DEBUG: file $datafilename is aligned to recordlen $rowLen")
                    }
                }
            }

            // open RandomAccessDataFile
            Files.newOutputStream(Paths.get(datafilename), APPEND, WRITE, CREATE).use { data ->
                var fibLog: FibonacciReporter? = null
                debug { fibLog = FibonacciReporter(size = null, noun = "appends") }

                // write rows
                msf.forEach { rowVec1: RowVec ->
                    val rowVec = transform?.let { it(rowVec1) } ?: rowVec1
                    if (first) {
                        meta0 = IsamMetaFileReader.write(metafilename, rowVec.right.α { it() }, varChars, fileOps)
                        rowLen = meta0.last().end
                        rowBuffer = ByteArray(rowLen) { 0 }
                        debug { logDebug { "toIsam: " + meta0.toList() } }
                        first = false
                    } else {
                        // Verify schema matches
                        val incomingMeta = rowVec.right.α { it() }
                        if (incomingMeta.size != meta0.size) {
                            throw IllegalArgumentException("Schema mismatch: incoming row has ${incomingMeta.size} columns, but file has ${meta0.size}")
                        }
                        for (i in 0 until meta0.size) {
                            val inc = incomingMeta[i]
                            val m0 = meta0[i]
                            if (inc.name != m0.name || inc.type != m0.type) {
                                throw IllegalArgumentException("Schema mismatch at column $i: expected ${m0.name}:${m0.type}, got ${inc.name}:${inc.type}")
                            }
                        }
                    }

                    WireProto.writeToBuffer(rowVec, rowBuffer, meta0)
                    data.write(rowBuffer)
                    debug { fibLog?.report()?.let { println(it) } }
                }
            }
        }
    }
}
