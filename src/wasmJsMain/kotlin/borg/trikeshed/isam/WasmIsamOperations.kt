package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.WasmFileOperations
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.isam.RecordMeta
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
            var dIdx = 0
            for (i in start until start + len) {
                d[dIdx++] = bytes[i]
            }
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

        val columnsByGroup = meta0.view.groupBy { (it as RecordMeta).groupName }
        val maxGroupId = meta0.view.map { (it as RecordMeta).groupId }.maxOrNull() ?: 0

        val groupRecordLengths = mutableMapOf<String, Int>()

        var maxRecordSize = 0
        for ((gname, cols) in columnsByGroup) {
            val groupRecordLen = cols.sumOf { (it as RecordMeta).end - (it as RecordMeta).begin }
            if (groupRecordLen > maxRecordSize) {
                maxRecordSize = groupRecordLen
            }
            groupRecordLengths[gname] = groupRecordLen
        }

        val rowBuf = ByteArray(maxRecordSize)

        cursor.iterator().forEach { rowVec ->
            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = groupRecordLengths[gname]!!
                val recordMetas = cols.map { it as RecordMeta }

                writeGroupToBuffer(rowVec, rowBuf, recordMetas, meta0 as Series<RecordMeta>)

                val gfilename = if ((cols.first() as RecordMeta).groupId == maxGroupId) datafilename else getGroupFilename(datafilename, gname)

                val existingBytes = if (fileOps.exists(gfilename)) fileOps.readAllBytes(gfilename) else ByteArray(0)
                val existingRecordCount = if (groupRecordLen > 0) existingBytes.size / groupRecordLen else 0
                val newBytes = ByteArray(existingRecordCount * groupRecordLen + groupRecordLen)
                copyIntoByteArray(existingBytes, newBytes)

                val offset = existingRecordCount * groupRecordLen
                copyIntoByteArray(rowBuf, newBytes, offset, 0, groupRecordLen)

                fileOps.write(gfilename, newBytes)
            }
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
        lateinit var meta0: Series<RecordMeta>
        var first = true

        var columnsByGroup: Map<String, List<RecordMeta>> = emptyMap()
        var maxGroupId = 0

        val groupRecordLengths = mutableMapOf<String, Int>()
        var rowBuf = ByteArray(0)

        msf.forEach { rowVec1: RowVec ->
            val rowVec = transform?.let { it(rowVec1) } ?: rowVec1
            if (first) {
                val cursorMeta: Series<ColumnMeta> = rowVec.right.α { it() }

                // We know IsamMetaFileReader returns RecordMeta wrapped in Series
                val recordMetas = IsamMetaFileReader.write(metafilename, cursorMeta, varChars, useMonocursorGroupings = useMonocursorGroupings)
                meta0 = recordMetas.size j { i -> recordMetas.b(i) as RecordMeta }

                columnsByGroup = meta0.view.groupBy { it.groupName }
                maxGroupId = meta0.view.map { it.groupId }.maxOrNull() ?: 0

                var maxRecordSize = 0
                for ((gname, cols) in columnsByGroup) {
                    val groupRecordLen = cols.sumOf { it.end - it.begin }
                    if (groupRecordLen > maxRecordSize) {
                        maxRecordSize = groupRecordLen
                    }
                    groupRecordLengths[gname] = groupRecordLen
                }
                rowBuf = ByteArray(maxRecordSize)
                first = false
            }

            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = groupRecordLengths[gname]!!
                val gfilename = if (cols.first().groupId == maxGroupId) datafilename else getGroupFilename(datafilename, gname)

                val recordGroupMetas = cols.map { it as RecordMeta }
                writeGroupToBuffer(rowVec, rowBuf, recordGroupMetas, meta0)

                val existingBytes = if (fileOps.exists(gfilename)) fileOps.readAllBytes(gfilename) else ByteArray(0)
                val existingRecordCount = if (groupRecordLen > 0) existingBytes.size / groupRecordLen else 0
                val newBytes = ByteArray(existingRecordCount * groupRecordLen + groupRecordLen)
                copyIntoByteArray(existingBytes, newBytes)

                val offset = existingRecordCount * groupRecordLen
                copyIntoByteArray(rowBuf, newBytes, offset, 0, groupRecordLen)

                fileOps.write(gfilename, newBytes)
            }
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
            var globalIdx = -1
            for (i in 0 until globalMeta.size) {
                if (globalMeta.b(i).name == colMeta.name) {
                    globalIdx = i
                    break
                }
            }
            if (globalIdx == -1) continue
            val colData = rowData.b(globalIdx)
            val colBytes = colMeta.encoder(colData)
            copyIntoByteArray(colBytes, rowBuf, localOffset, 0, colBytes.size)
            localOffset += colMeta.end - colMeta.begin
        }
    }

    private fun copyIntoByteArray(source: ByteArray, destination: ByteArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = source.size) {
        var dIdx = destinationOffset
        for (i in startIndex until endIndex) {
            destination[dIdx++] = source[i]
        }
    }
}

actual fun defaultIsamOperations(): IsamOperations = WasmIsamOperations()
