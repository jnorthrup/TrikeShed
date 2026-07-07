package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.WasmFileOperations
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*

class WasmIsamDataReader(
    val datafileFilename: String,
    val metafileFilename: String,
    val metafile: IsamMetaFileReader
) : IsamDataReader {
    private val fileOps: FileOperations = WasmFileOperations()
    private val constraints: Series<RecordMeta> get() = metafile.constraints
    private val columnsByGroup: Map<String, List<RecordMeta>> by lazy {
        constraints.view.groupBy { it.groupName }
    }
    private val maxGroupId: Int by lazy {
        constraints.view.map { it.groupId }.maxOrNull() ?: 0
    }
    private val groupBuffers = mutableMapOf<String, ByteArray>()

    override val recordCount: Int
        get() {
            val primaryGname = columnsByGroup.entries.firstOrNull { it.value.first().groupId == maxGroupId }?.key ?: "0"
            val bytes = groupBuffers[primaryGname] ?: return 0
            val groupCols = columnsByGroup[primaryGname] ?: return 0
            val groupRecordLen = groupCols.sumOf { it.end - it.begin }
            return if (groupRecordLen > 0) bytes.size / groupRecordLen else 0
        }

    override val readRow: (Int) -> RowVec = { row: Int ->
        constraints.size j { colIdx ->
            val constraint = constraints[colIdx]
            val gname = constraint.groupName
            val colsInGroup = columnsByGroup[gname]!!
            val groupRecordLen = colsInGroup.sumOf { it.end - it.begin }
            val localOffset = colsInGroup.takeWhile { it != constraint }.sumOf { it.end - it.begin }
            val len = constraint.end - constraint.begin
            
            val bytes = groupBuffers[gname]!!
            val start = row * groupRecordLen + localOffset
            val d = ByteArray(len)
            bytes.copyInto(d, 0, start, start + len)
            constraint.decoder(d) j { -> constraint }
        }
    }

    override fun open() {
        metafile.open()
        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) datafileFilename else getGroupFilename(datafileFilename, gname)
            groupBuffers[gname] = if (fileOps.exists(gfilename)) fileOps.readAllBytes(gfilename) else ByteArray(0)
        }
    }

    override fun close() {
        metafile.close()
    }
}

class WasmIsamOperations : IsamOperations {
    override fun createReader(
        datafileFilename: String,
        metafileFilename: String,
        metafile: IsamMetaFileReader
    ): IsamDataReader = WasmIsamDataReader(datafileFilename, metafileFilename, metafile)

    override fun write(
        cursor: Cursor,
        datafilename: String,
        varChars: Map<String, Int>,
        useMonocursorGroupings: Boolean
    ) {
        val fileOps: FileOperations = WasmFileOperations()
        val metafilename = "$datafilename.meta"

        val row0 = cursor.b(0)
        val cursorMeta: Series<ColumnMeta> = row0.a j { c: Int -> row0.b(c).b() }
        val meta0 = IsamMetaFileReader.write(metafilename, cursorMeta, varChars, useMonocursorGroupings = useMonocursorGroupings)

        val columnsByGroup = meta0.view.groupBy { it.groupName }
        val maxGroupId = meta0.view.map { it.groupId }.maxOrNull() ?: 0

        val groupBuffers = mutableMapOf<String, ByteArray>()
        val groupOffsets = mutableMapOf<String, Int>()

        for ((gname, cols) in columnsByGroup) {
            val groupRecordLen = cols.sumOf { it.end - it.begin }
            groupBuffers[gname] = ByteArray(groupRecordLen * cursor.size)
            groupOffsets[gname] = 0
        }

        cursor.iterator().forEach { rowVec ->
            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val rowBuf = ByteArray(groupRecordLen)
                writeGroupToBuffer(rowVec, rowBuf, cols, meta0)
                val out = groupBuffers[gname]!!
                val offset = groupOffsets[gname]!!
                rowBuf.copyInto(out, offset, 0, groupRecordLen)
                groupOffsets[gname] = offset + groupRecordLen
            }
        }

        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) datafilename else getGroupFilename(datafilename, gname)
            fileOps.write(gfilename, groupBuffers[gname]!!)
        }
    }

    override fun append(
        msf: Iterable<RowVec>,
        datafilename: String,
        varChars: Map<String, Int>,
        transform: ((RowVec) -> RowVec)?,
        useMonocursorGroupings: Boolean
    ) {
        val fileOps: FileOperations = WasmFileOperations()
        val metafilename = "$datafilename.meta"
        val meta0: Series<RecordMeta> = if (fileOps.exists(metafilename)) {
            val reader = IsamMetaFileReader(metafilename)
            reader.open()
            reader.constraints
        } else {
            val rows = msf.map { transform?.invoke(it) ?: it }.toList()
            val cursor = rows.toSeries()
            write(cursor, datafilename, varChars, useMonocursorGroupings)
            return
        }

        val columnsByGroup = meta0.view.groupBy { it.groupName }
        val maxGroupId = meta0.view.map { it.groupId }.maxOrNull() ?: 0

        val groupBuffers = mutableMapOf<String, ByteArray>()
        val groupOffsets = mutableMapOf<String, Int>()

        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) datafilename else getGroupFilename(datafilename, gname)
            val existing = if (fileOps.exists(gfilename)) fileOps.readAllBytes(gfilename) else ByteArray(0)
            
            val groupRecordLen = cols.sumOf { it.end - it.begin }
            val out = ByteArray(existing.size + (groupRecordLen * msf.count()))
            existing.copyInto(out, 0, 0, existing.size)
            
            groupBuffers[gname] = out
            groupOffsets[gname] = existing.size
        }

        msf.forEach { rowVec ->
            val rv = transform?.invoke(rowVec) ?: rowVec
            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val rowBuf = ByteArray(groupRecordLen)
                writeGroupToBuffer(rv, rowBuf, cols, meta0)
                
                val out = groupBuffers[gname]!!
                val offset = groupOffsets[gname]!!
                rowBuf.copyInto(out, offset, 0, groupRecordLen)
                groupOffsets[gname] = offset + groupRecordLen
            }
        }

        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) datafilename else getGroupFilename(datafilename, gname)
            fileOps.write(gfilename, groupBuffers[gname]!!)
        }
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

actual fun defaultIsamOperations(): IsamOperations = WasmIsamOperations()
