@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.*
import borg.trikeshed.userspace.openUserspaceChannelBackend
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.nio.file.Files as UserspaceFiles
import borg.trikeshed.userspace.nio.file.File as UserspaceFile
import borg.trikeshed.userspace.nio.ByteBuffer as UserspaceByteBuffer

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
    private val groupFiles = mutableMapOf<String, UserspaceFile>()

    private val primaryGroupFilename: String by lazy {
        val primaryGname = columnsByGroup.entries.firstOrNull { entry ->
            entry.value.first().groupId == maxGroupId
        }?.key ?: "0"
        
        primaryGname
    }

    override val recordCount: Int by lazy {
        val file = groupFiles[primaryGroupFilename] ?: groupFiles.values.first()
        val groupCols = columnsByGroup[primaryGroupFilename] ?: columnsByGroup.values.first()
        val groupRecordLen = groupCols.sumOf { it.end - it.begin }
        (file.size().toInt() / groupRecordLen)
    }

    override val readRow: (Int) -> RowVec = { row ->
        val groupBuffers = mutableMapOf<String, ByteArray>()
        val backend = borg.trikeshed.userspace.openUserspaceChannelBackend(32)
        val submissions = mutableListOf<borg.trikeshed.userspace.UringOp.Companion.UringSubmission>()

        var userData = 1L
        val gnames = mutableListOf<String>()
        val nioBufs = mutableListOf<java.nio.ByteBuffer>()

        for ((gname, cols) in columnsByGroup) {
            val file = groupFiles[gname]!!
            val groupRecordLen = cols.sumOf { it.end - it.begin }
            val nioBuf = java.nio.ByteBuffer.allocateDirect(groupRecordLen)
            
            val addr = nioBuf.let { java.lang.reflect.Field::class.java.getDeclaredField("address").apply { isAccessible = true } }.run { getLong(this) }
            
            val wrapperBuf = borg.trikeshed.userspace.nio.ByteBuffer(nioBuf.array()) // Fake wrapper for compat
            submissions.add(borg.trikeshed.userspace.UringOp.Companion.Submissions.read(
                fd = file.id,
                bufAddr = addr,
                len = groupRecordLen,
                offset = row * groupRecordLen.toLong(),
                userData = userData++
            ).copy(buffer = wrapperBuf))
            
            gnames.add(gname)
            nioBufs.add(nioBuf)
        }

        backend.submitBatch(submissions)

        for (i in gnames.indices) {
            val buf = nioBufs[i]
            val arr = ByteArray(buf.capacity())
            buf.position(0)
            buf.get(arr)
            groupBuffers[gnames[i]] = arr
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
            groupFiles[gname] = UserspaceFiles.open(gfilename, readOnly = true)
        }
    }

    override fun close() {
        groupFiles.values.forEach { it.close() }
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

        val groupFiles = mutableMapOf<String, UserspaceFile>()
        val offsets = mutableMapOf<String, Long>()

        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) {
                datafilename
            } else {
                getGroupFilename(datafilename, gname)
            }
            
            groupFiles[gname] = UserspaceFiles.open(gfilename, readOnly = false)
            offsets[gname] = 0L
        }

        val backend = borg.trikeshed.userspace.openUserspaceChannelBackend(32)
        var userData = 1L

        cursor.iterator().forEach { rowVec ->
            val submissions = mutableListOf<borg.trikeshed.userspace.UringOp.Companion.UringSubmission>()
            
            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val rowBuffer = ByteArray(groupRecordLen)
                
                writeGroupToBuffer(rowVec, rowBuffer, cols, meta0)
                
                val nioBuf = java.nio.ByteBuffer.allocateDirect(groupRecordLen)
                nioBuf.put(rowBuffer)
                nioBuf.position(0)
                
                val file = groupFiles[gname]!!
                val addr = nioBuf.let { java.lang.reflect.Field::class.java.getDeclaredField("address").apply { isAccessible = true } }.run { getLong(this) }
                
                val currentOffset = offsets[gname]!!
                submissions.add(borg.trikeshed.userspace.UringOp.Companion.Submissions.write(
                    fd = file.id,
                    bufAddr = addr,
                    len = groupRecordLen,
                    offset = currentOffset,
                    userData = userData++
                ).copy(buffer = borg.trikeshed.userspace.nio.ByteBuffer(rowBuffer)))
                offsets[gname] = currentOffset + groupRecordLen
            }
            backend.submitBatch(submissions)
        }

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
        
                val groupFiles = mutableMapOf<String, UserspaceFile>()
        val offsets = mutableMapOf<String, Long>()
        var userData = 1L

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
                    val file = UserspaceFiles.open(gfilename, readOnly = false)
                    groupFiles[gname] = file
                    offsets[gname] = file.size().takeIf { it >= 0 } ?: 0L
                }
                first = false
            }

            val submissions = mutableListOf<borg.trikeshed.userspace.UringOp.Companion.UringSubmission>()

            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val rowBuffer = ByteArray(groupRecordLen)
                
                writeGroupToBuffer(rowVec, rowBuffer, cols, meta0)
                
                val nioBuf = java.nio.ByteBuffer.allocateDirect(groupRecordLen)
                nioBuf.put(rowBuffer)
                nioBuf.position(0)
                
                val file = groupFiles[gname]!!
                val addr = nioBuf.let { java.lang.reflect.Field::class.java.getDeclaredField("address").apply { isAccessible = true } }.run { getLong(this) }
                
                val currentOffset = offsets[gname]!!
                submissions.add(Submissions.write(
                    fd = file.id,
                    bufAddr = addr,
                    len = groupRecordLen,
                    offset = currentOffset,
                    userData = userData++
                ).copy(buffer = UserspaceByteBuffer(rowBuffer)))
                offsets[gname] = currentOffset + groupRecordLen
            }
            val backend = openUserspaceChannelBackend(32)
            backend.submitBatch(submissions)
        }

        groupFiles.values.forEach { it.close() }
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
