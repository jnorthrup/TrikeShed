package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.*
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IsamColumnarGroupTest {

    @Test
    fun testColumnarGroupingFileSplits() {
        val tempDir = createTempDirectory()
        val dataFilename = tempDir.resolve("test_split.bin").toString()
        val metaFilename = "$dataFilename.meta"
        val reservedFilename = tempDir.resolve("test_split.reserved.bin").toString()

        // 1. Define ColumnMeta with Groupings
                // colA: Group 1 (default)
        val colA = RecordMeta(
            name = "colA",
            type = IOMemento.IoInt,
            begin = 0,
            end = 4
        ).apply {
            groupId = 1
            groupName = "default"
        }

        // colB: Group 0 (reserved)
        val colB = RecordMeta(
            name = "colB",
            type = IOMemento.IoInt,
            begin = 4,
            end = 8
        ).apply {
            groupId = 0
            groupName = "reserved"
        }

        // colC: Group 1 (default)
        val colC = RecordMeta(
            name = "colC",
            type = IOMemento.IoInt,
            begin = 8,
            end = 12
        ).apply {
            groupId = 1
            groupName = "default"
        }

        val metaList: Series<RecordMeta> = listOf(colA, colB, colC).toSeries()

        // 2. Define a Row with values
        val rowValues: Series<Any> = listOf(42, 100, 24).toSeries()
        val rowVec: RowVec = rowValues.size j { colIdx ->
            rowValues[colIdx] j { metaList[colIdx] }
        }

        val cursor: Cursor = listOf(rowVec).toSeries()

        // 3. Write ISAM Data File
        IsamDataFile.write(cursor, dataFilename)

        // 4. Assert Metafile was written and correctly parsed
        assertTrue(Files.exists(Paths.get(metaFilename)), "Meta file should exist")
        val reader = IsamMetaFileReader(metaFilename)
        reader.open()
        assertEquals(3, reader.constraints.size, "Should parse 3 constraints")
        assertEquals(0, reader.constraints[1].groupId, "colB should have groupId 0")
        assertEquals("reserved", reader.constraints[1].groupName, "colB should have groupName 'reserved'")

        // 5. Assert Column Group splits are written to disk
        assertTrue(Files.exists(Paths.get(dataFilename)), "Primary default file should exist")
        assertTrue(Files.exists(Paths.get(reservedFilename)), "Grouped 'reserved' file should exist")

        // 6. Read back using IsamDataFile
        val isamData = IsamDataFile(dataFilename)
        isamData.open()
        assertEquals(1, isamData.size, "Should contain 1 record")

        val readRow = isamData.b(0)
        assertEquals(42, readRow[0].a, "colA should be 42")
        assertEquals(100, readRow[1].a, "colB should be 100")
        assertEquals(24, readRow[2].a, "colC should be 24")

        isamData.close()
    }
}
