@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.isam

import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import platform.posix.*
import simple.PosixFile
import simple.PosixOpenOpts

class PosixMmapInfo(
    val data: COpaquePointer,
    val fileSize: Long,
    val groupRecordLen: Int
)

class PosixIsamDataReader(
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
    
    private val groupMmaps = mutableMapOf<String, PosixMmapInfo>()
    private var first = true

    override val recordCount: Int
        get() {
            open()
            val primaryGname = columnsByGroup.entries.firstOrNull { it.value.first().groupId == maxGroupId }?.key ?: "0"
            val info = groupMmaps[primaryGname] ?: groupMmaps.values.first()
            return (info.fileSize / info.groupRecordLen).toInt()
        }

    override val readRow: (Int) -> RowVec = { row ->
        memScoped {
            constraints.size j { colIdx ->
                val constraint = constraints[colIdx]
                val gname = constraint.groupName
                val colsInGroup = columnsByGroup[gname]!!
                val mmapInfo = groupMmaps[gname]!!
                
                val localOffset = colsInGroup.takeWhile { it != constraint }.sumOf { it.end - it.begin }
                val len = constraint.end - constraint.begin
                
                val d2 = mmapInfo.data.toLong() + (row * mmapInfo.groupRecordLen) + localOffset
                val d5: COpaquePointer = d2.toCPointer()!!
                val d6: ByteArray = d5.readBytes(len)
                constraint.decoder(d6)!! j { constraint }
            }
        }
    }

    override fun open() {
        if (!first) return
        first = false
        metafile.open()
        
        memScoped {
            for (gname in columnsByGroup.keys) {
                val cols = columnsByGroup[gname]!!
                val firstCol = cols.first()
                val gfilename = if (firstCol.groupId == maxGroupId) {
                    datafileFilename
                } else {
                    getGroupFilename(datafileFilename, gname)
                }
                
                val fd = open(gfilename, O_RDONLY)
                val stat = alloc<stat>()
                fstat(fd, stat.ptr)
                val fileSize = stat.st_size
                val groupRecordLen = cols.sumOf { it.end - it.begin }

                require(fileSize % groupRecordLen == 0L) { "fileSize of $gfilename must be a multiple of $groupRecordLen" }

                val data = mmap(null, fileSize.toULong(), PROT_READ, MAP_PRIVATE, fd, 0)!!
                close(fd)
                
                groupMmaps[gname] = PosixMmapInfo(data, fileSize, groupRecordLen)
            }
        }
    }

    override fun close() {
        memScoped {
            for (info in groupMmaps.values) {
                munmap(info.data, info.fileSize.toULong())
            }
        }
        metafile.close()
    }
}

class PosixIsamOperations : IsamOperations {
    override fun createReader(
        datafileFilename: String,
        metafileFilename: String,
        metafile: IsamMetaFileReader
    ): IsamDataReader = PosixIsamDataReader(datafileFilename, metafileFilename, metafile)

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

        val groupFiles = mutableMapOf<String, PosixFile>()

        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) {
                datafilename
            } else {
                getGroupFilename(datafilename, gname)
            }
            groupFiles[gname] = PosixFile(
                gfilename,
                PosixOpenOpts.withFlags(PosixOpenOpts.O_Creat, PosixOpenOpts.O_Trunc, PosixOpenOpts.O_Rdwr)
            )
        }

        cursor.iterator().forEach { rowVec ->
            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val rowBuffer = ByteArray(groupRecordLen)
                writeGroupToBuffer(rowVec, rowBuffer, cols, meta0)
                groupFiles[gname]!!.write(rowBuffer)
            }
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
        TODO("append not implemented for PosixIsamOperations")
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

actual fun defaultIsamOperations(): IsamOperations = PosixIsamOperations()
