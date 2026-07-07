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
    private val constraints: Series<RecordMeta> get() = metafile.constraints
    private val columnsByGroup: Map<String, List<RecordMeta>> by lazy {
        constraints.view.groupBy { it.groupName }
    }
    private val maxGroupId: Int by lazy {
        constraints.view.map { it.groupId }.maxOrNull() ?: 0
    }
    private val groupChannels = mutableMapOf<String, SeekableByteChannel>()
    private val lock: ReentrantLock = ReentrantLock()

    private val primaryGroupFilename: String by lazy {
        val primaryGname = columnsByGroup.entries.firstOrNull { entry ->
            entry.value.first().groupId == maxGroupId
        }?.key ?: "0"
        
        primaryGname
    }

    override val recordCount: Int by lazy {
        val channel = groupChannels[primaryGroupFilename] ?: groupChannels.values.first()
        val groupCols = columnsByGroup[primaryGroupFilename] ?: columnsByGroup.values.first()
        val groupRecordLen = groupCols.sumOf { it.end - it.begin }
        (channel.size().toInt() / groupRecordLen)
    }

    override val readRow: (Int) -> RowVec = { row ->
        val groupBuffers = mutableMapOf<String, ByteArray>()
        lock.lock()
        try {
            for ((gname, cols) in columnsByGroup) {
                val channel = groupChannels[gname]!!
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val buffer = ByteBuffer.allocate(groupRecordLen)
                channel.position(row * groupRecordLen.toLong())
                channel.read(buffer)
                groupBuffers[gname] = buffer.position(0).array()
            }
        } finally {
            lock.unlock()
        }

        constraints.size j { colIdx ->
            val constraint = constraints[colIdx]
            val gname = constraint.groupName
            val colsInGroup = columnsByGroup[gname]!!
            val localOffset = colsInGroup.takeWhile { it != constraint }.sumOf { it.end - it.begin }
            val len = constraint.end - constraint.begin
            val s = groupBuffers[gname]!!.sliceArray(localOffset until localOffset + len)
            constraint.decoder(s)!! j { constraint }
        }
    }

    override fun open() {
        metafile.open()
        
        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) {
                datafileFilename
            } else {
                getGroupFilename(datafileFilename, gname)
            }
            groupChannels[gname] = Files.newByteChannel(Paths.get(gfilename), READ)
        }
    }

    override fun close() {
        groupChannels.values.forEach { it.close() }
        metafile.close()
    }
}

class JvmIsamOperations : IsamOperations {
    override fun createReader(
        datafileFilename: String,
        metafileFilename: String,
        metafile: IsamMetaFileReader
    ): IsamDataReader = JvmIsamDataReader(datafileFilename, metafileFilename, metafile)

    override fun write(
        cursor: Cursor,
        datafilename: String,
        varChars: Map<String, Int>,
        useMonocursorGroupings: Boolean
    ) {
        val metafilename = "$datafilename.meta"

        val row0 = cursor.b(0)
        val cursorMeta: Series<ColumnMeta> = row0.a j { c: Int -> row0.b(c).b() }
        val meta0 = IsamMetaFileReader.write(metafilename, cursorMeta, varChars, useMonocursorGroupings = useMonocursorGroupings)

        val columnsByGroup = meta0.view.groupBy { it.groupName }
        val maxGroupId = meta0.view.map { it.groupId }.maxOrNull() ?: 0

        val groupChannels = mutableMapOf<String, SeekableByteChannel>()
        val groupFiles = mutableMapOf<String, RandomAccessFile>()

        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) {
                datafilename
            } else {
                getGroupFilename(datafilename, gname)
            }
            val raf = RandomAccessFile(gfilename, "rw")
            groupFiles[gname] = raf
            groupChannels[gname] = raf.channel
        }

        cursor.iterator().forEach { rowVec ->
            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val rowBuffer1 = ByteBuffer.allocate(groupRecordLen)
                val rowBuffer = rowBuffer1.array()
                
                writeGroupToBuffer(rowVec, rowBuffer, cols, meta0)
                rowBuffer1.position(0)
                groupChannels[gname]!!.write(rowBuffer1)
            }
        }

        groupChannels.values.forEach { it.close() }
        groupFiles.values.forEach { it.close() }
    }

    override fun append(
        msf: Iterable<RowVec>,
        datafilename: String,
        varChars: Map<String, Int>,
        transform: ((RowVec) -> RowVec)?,
        useMonocursorGroupings: Boolean
    ) {
        val metafilename = "$datafilename.meta"
        lateinit var meta0: Series<RecordMeta>
        var first = true

        var columnsByGroup: Map<String, List<RecordMeta>> = emptyMap()
        var maxGroupId = 0
        val groupStreams = mutableMapOf<String, java.io.OutputStream>()

        msf.forEach { rowVec1: RowVec ->
            val rowVec = transform?.let { it(rowVec1) } ?: rowVec1
            if (first) {
                meta0 = IsamMetaFileReader.write(metafilename, rowVec.right.α { it() }, varChars, useMonocursorGroupings = useMonocursorGroupings)
                columnsByGroup = meta0.view.groupBy { it.groupName }
                maxGroupId = meta0.view.map { it.groupId }.maxOrNull() ?: 0

                for (gname in columnsByGroup.keys) {
                    val cols = columnsByGroup[gname]!!
                    val firstCol = cols.first()
                    val gfilename = if (firstCol.groupId == maxGroupId) {
                        datafilename
                    } else {
                        getGroupFilename(datafilename, gname)
                    }
                    groupStreams[gname] = Files.newOutputStream(Paths.get(gfilename), APPEND, WRITE, CREATE)
                }
                first = false
            }

            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val rowBuffer = ByteArray(groupRecordLen)
                writeGroupToBuffer(rowVec, rowBuffer, cols, meta0)
                groupStreams[gname]!!.write(rowBuffer)
            }
        }

        groupStreams.values.forEach { it.close() }
    }

    private fun writeGroupToBuffer(
        rowVec: RowVec,
        rowBuf: ByteArray,
        groupMeta: List<RecordMeta>,
        globalMeta: Series<RecordMeta>
    ) {
        val rowData = rowVec.left
        var localOffset = 0
        for (colMeta in groupMeta) {
            val globalIdx = globalMeta.view.indexOf(colMeta)
            val colData = rowData[globalIdx]
            val colBytes = colMeta.encoder(colData)
            colBytes.copyInto(rowBuf, localOffset, 0, colBytes.size)
            localOffset += colMeta.end - colMeta.begin
        }
    }
}

actual fun defaultIsamOperations(): IsamOperations = JvmIsamOperations()
