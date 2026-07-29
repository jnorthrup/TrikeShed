I'll resolve the conflicts in this file by carefully examining both sides of the merge and understanding the context.

Let me analyze the conflicts:

1. **First conflict** (around line 47): The `readRow` property has conflicting implementations
2. **Second conflict** (around line 77): The `open()` method has conflicting implementations
3. **Third conflict** (around line 106): The `write` method has conflicting implementations

Let me resolve each conflict:

**Conflict 1 Analysis:**
- Left side (JVM): Uses `ByteBuffer.allocateDirect()` for memory allocation
- Right side (Wasm): Uses `ByteArray` and reuses buffers in a loop

**Conflict 2 Analysis:**
- Left side (JVM): Opens metafile and reads group files into buffers
- Right side (Wasm): Similar but with different structure

**Conflict 3 Analysis:**
- Left side (JVM): Uses ByteBuffer for writing
- Right side (Wasm): Uses ByteArray for writing

Now I'll create the resolved file:

```kotlin
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
        val groupRowBufs = mutableMapOf<String, ByteArray>()
        val groupRecordLengths = mutableMapOf<String, Int>()

        var maxRecordSize = 0
        for ((gname, cols) in columnsByGroup) {
            val groupRecordLen = cols.sumOf { it.end - it.begin }
            if (groupRecordLen > maxRecordSize) {
                maxRecordSize = groupRecordLen
            }
        }

        for ((gname, cols) in columnsByGroup) {
            val groupRecordLen = cols.sumOf { it.end - it.begin }
            val groupRowBuf = ByteArray(groupRecordLen)
            groupRowBufs[gname] = groupRowBuf
            groupRecordLengths[gname] = groupRecordLen
            groupOffsets[gname] = 0
        }

        val row = cursor.b(0)
        val rowSize = row.a
        for (i in 0 until rowSize) {
            val col = row.b(i)
            val colMeta = col.b()
            val gname = colMeta.groupName
            val colsInGroup = columnsByGroup[gname]!!
            val groupRecordLen = groupRecordLengths[gname]!!
            val groupRowBuf = groupRowBufs[gname]!!
            val localOffset = colsInGroup.takeWhile { it != colMeta }.sumOf { it.end - it.begin }
            val len = colMeta.end - colMeta.begin
            
            val bytes = col.a
            val start = localOffset
            bytes.copyInto(groupRowBuf, start, 0, len)
        }

        for ((gname, cols) in columnsByGroup) {
            val groupRecordLen = groupRecordLengths[gname]!!
            val groupRowBuf = groupRowBufs[gname]!!
            val gfilename = if (cols.first().groupId == maxGroupId) datafilename else getGroupFilename(datafilename, gname)
            
            val existingBytes = if (fileOps.exists(gfilename)) fileOps.readAllBytes(gfilename) else ByteArray(0)
            val existingRecordCount = if (groupRecordLen > 0) existingBytes.size / groupRecordLen else 0
            val newBytes = ByteArray(existingRecordCount * groupRecordLen + groupRecordLen)
            existingBytes.copyInto(newBytes)
            
            val offset = existingRecordCount * groupRecordLen
            groupRowBuf.copyInto(newBytes, offset)
            
            fileOps.writeAllBytes(gfilename, newBytes)
        }
    }
}
```

The key changes made:

1. **Kept the WasmIsamDataReader implementation** from the right side (Wasm-specific) since this is a Wasm file
2. **Kept the WasmIsamOperations.write() implementation** from the right side (Wasm-specific) since this is a Wasm file
3. **Removed the ByteBuffer-based implementation** from the left side (JVM-specific) since this is a Wasm file
4. **Maintained the ByteArray-based approach** throughout for consistency with the Wasm platform
5. **Preserved all imports** as they were already correct for the Wasm implementation

The resolved file now properly implements the ISAM operations using ByteArray instead of ByteBuffer, which is appropriate for the Wasm platform.