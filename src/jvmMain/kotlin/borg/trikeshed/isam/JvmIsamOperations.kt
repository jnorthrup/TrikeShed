@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.*
import java.util.concurrent.locks.ReentrantLock

class JvmIsamDataReader(
    val datafileFilename: String,
    val metafileFilename: String,
    val metafile: IsamMetaFileReader
) : IsamDataReader {
    private val data: SeekableByteChannel by lazy {
        Files.newByteChannel(
            Paths.get(datafileFilename),
            READ
        )
    }

    private val recordlen: Int by lazy { metafile.recordlen }
    private val fileSize: Long get() = data.size()
    override val recordCount: Int by lazy { fileSize.toInt() / recordlen }

    private val constraints: Series<RecordMeta> get() = metafile.constraints
    private val lock: ReentrantLock = ReentrantLock()

    override val readRow: (Int) -> RowVec = { row ->
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

    override fun open() {
        metafile.open()
        // report on record alignment of the file
        val alignment = fileSize % recordlen
        if (alignment != 0L) {
            println("WARN: file $datafileFilename is not aligned to recordlen $recordlen")
        } else
            println("DEBUG: file $datafileFilename is aligned to recordlen $recordlen")

        val fieldCounts: Map<IOMemento, Pair<Int, Int>> = constraints.`▶`.groupBy { it.type }
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

    override fun close() {
        data.close()
        metafile.close()
    }
}

class JvmIsamOperations : IsamOperations {
    override fun createReader(
        datafileFilename: String,
        metafileFilename: String,
        metafile: IsamMetaFileReader
    ): IsamDataReader = JvmIsamDataReader(datafileFilename, metafileFilename, metafile)

    override fun write(cursor: Cursor, datafilename: String, varChars: Map<String, Int>) {
        val metafilename = "$datafilename.meta"

        val row0 = cursor.b(0)
        val cursorMeta: Series<ColumnMeta> = row0.a j { c: Int -> row0.b(c).b() }
        val meta0 = IsamMetaFileReader.write(metafilename, cursorMeta, varChars)

        val randomAccessFile = RandomAccessFile(datafilename, "rw")
        val data = randomAccessFile.channel

        val last = meta0.last()
        val rowLen = last.end
        val rowBuffer1 = ByteBuffer.allocate(rowLen)
        val rowBuffer = rowBuffer1.array()

        cursor.iterator().forEach { rowVec ->
            WireProto.writeToBuffer(rowVec, rowBuffer, meta0)
            rowBuffer1.position(0)
            data.write(rowBuffer1)
        }
        randomAccessFile.close()
        data.close()
    }

    override fun append(
        msf: Iterable<RowVec>,
        datafilename: String,
        varChars: Map<String, Int>,
        transform: ((RowVec) -> RowVec)?
    ) {
        val metafilename = "$datafilename.meta"
        lateinit var meta0: Series<RecordMeta>
        val data = Files.newOutputStream(Paths.get(datafilename), APPEND, WRITE, CREATE)

        var last: RecordMeta
        var rowLen = 0
        lateinit var rowBuffer: ByteArray
        var fibLog: FibonacciReporter? = null
        debug { fibLog = FibonacciReporter(size = null, noun = "appends") }
        var first = true

        msf.forEach { rowVec1: RowVec ->
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
            data.write(rowBuffer)
            debug { fibLog?.report()?.let { println(it) } }
        }
        data.close()
    }
}

actual fun defaultIsamOperations(): IsamOperations = JvmIsamOperations()
