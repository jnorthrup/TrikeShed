@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("UNCHECKED_CAST")
package borg.trikeshed.isam
import borg.trikeshed.common.Usable
import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*
import kotlinx.cinterop.*
import platform.posix.*
class MingwIsamDataReader(
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
    private val groupFiles = mutableMapOf<String, Int>()
    private val groupRecordLens = mutableMapOf<String, Int>()
    private var first = true
    override val recordCount: Int
        get() {
            open()
            val primaryGname = columnsByGroup.entries.firstOrNull { it.value.first().groupId == maxGroupId }?.key ?: "0"
            val fd = groupFiles[primaryGname] ?: groupFiles.values.first()
            val stat = memScoped { alloc<stat>().apply { fstat(fd, ptr) } }
            val len = groupRecordLens[primaryGname]!!
            return (stat.st_size / len).toInt()
        }
    override val readRow: (Int) -> RowVec = { row ->
        memScoped {
            constraints.size j { colIdx ->
                val constraint = constraints[colIdx]
                val gname = constraint.groupName
                val colsInGroup = columnsByGroup[gname]!!
                val localOffset = colsInGroup.takeWhile { it != constraint }.sumOf { it.end - it.begin }
                val len = constraint.end - constraint.begin
                val groupRecordLen = groupRecordLens[gname]!!
                val fd = groupFiles[gname]!!
                val fileOffset = (row.toLong() * groupRecordLen) + localOffset
                val buf = allocArray<ByteVar>(len)
                lseek(fd, fileOffset.convert(), SEEK_SET)
                val bytesRead = read(fd, buf, len.convert())
                if (bytesRead < len.toLong()) {
                    throw IllegalStateException("Failed to read expected bytes from ISAM file. Expected $len, got $bytesRead")
                }
                val byteArray = ByteArray(len) { buf[it] }
                constraint.decoder(byteArray)!! j { constraint }
            }
        }
    }
    override fun open() {
        if (!first) return
        first = false
        metafile.open()
        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) {
                datafileFilename
            } else {
                getGroupFilename(datafileFilename, gname)
            }
            val fd = open(gfilename, O_RDONLY or O_BINARY)
            if (fd < 0) {
                throw IllegalStateException("Failed to open $gfilename")
            }
            groupFiles[gname] = fd
            groupRecordLens[gname] = cols.sumOf { it.end - it.begin }
        }
    }
    override fun close() {
        for (fd in groupFiles.values) {
            close(fd)
        }
        metafile.close()
    }
}
class MingwIsamOperations : IsamOperations {
    override fun createReader(
        datafileFilename: String,
        metafileFilename: String,
        metafile: IsamMetaFileReader
    ): IsamDataReader = MingwIsamDataReader(datafileFilename, metafileFilename, metafile)
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
        val groupFiles = mutableMapOf<String, Int>()
        for (gname in columnsByGroup.keys) {
            val cols = columnsByGroup[gname]!!
            val firstCol = cols.first()
            val gfilename = if (firstCol.groupId == maxGroupId) {
                datafilename
            } else {
                getGroupFilename(datafilename, gname)
            }
            val fd = open(gfilename, O_CREAT or O_TRUNC or O_RDWR, O_BINARY, 0x1b6 /* 0666 */)
            if (fd < 0) throw IllegalStateException("Failed to open $gfilename")
            groupFiles[gname] = fd
        }
        cursor.iterator().forEach { rowVec ->
            for ((gname, cols) in columnsByGroup) {
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                val rowBuffer = ByteArray(groupRecordLen)
                writeGroupToBuffer(rowVec, rowBuffer, cols, meta0)
                val fd = groupFiles[gname]!!
                memScoped {
                    val buf = allocArray<ByteVar>(groupRecordLen)
                    rowBuffer.forEachIndexed { index, byte -> buf[index] = byte }
                    write(fd, buf, groupRecordLen.convert())
                }
            }
        }
        try { groupFiles.values.forEach { close(it) } } finally { }
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
        val groupFiles = mutableMapOf<String, Int>()
        val groupRowBuffers = mutableMapOf<String, ByteArray>()
        msf.forEach { rowVec1: RowVec ->
            val rowVec = transform?.let { it(rowVec1) } ?: rowVec1
            if (first) {
                meta0 = IsamMetaFileReader.write(
                    metafilename,
                    rowVec.right.α { it() },
                    varChars,
                    useMonocursorGroupings = useMonocursorGroupings
                )
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
                    val fd = open(gfilename, O_CREAT or O_APPEND or O_RDWR, O_BINARY, 0x1b6 /* 0666 */)
                    if (fd < 0) throw IllegalStateException("Failed to open $gfilename")
                    groupFiles[gname] = fd
                    val groupRecordLen = cols.sumOf { it.end - it.begin }
                    val rowBuffer = ByteArray(groupRecordLen)
                    groupRowBuffers[gname] = rowBuffer
                }
                first = false
            }
            for ((gname, cols) in columnsByGroup) {
                val rowBuffer = groupRowBuffers[gname]!!
                val groupRecordLen = cols.sumOf { it.end - it.begin }
                writeGroupToBuffer(rowVec, rowBuffer, cols, meta0)
                val fd = groupFiles[gname]!!
                memScoped {
                    val buf = allocArray<ByteVar>(groupRecordLen)
                    rowBuffer.forEachIndexed { index, byte -> buf[index] = byte }
                    write(fd, buf, groupRecordLen.convert())
                }
            }
        }
        try { groupFiles.values.forEach { close(it) } } finally { }
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
actual fun defaultIsamOperations(): IsamOperations = MingwIsamOperations()
