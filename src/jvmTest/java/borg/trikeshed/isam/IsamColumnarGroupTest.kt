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

        // 3. Write ISAM Data File (turn off automatic monocursor groupings to test explicit groups)
        IsamDataFile.write(cursor, dataFilename, useMonocursorGroupings = false)

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

    @Test
    fun testMonocursorGroupingsDefaultingOn() {
        val tempDir = createTempDirectory()
        val dataFilename = tempDir.resolve("test_mono.bin").toString()
        val metaFilename = "$dataFilename.meta"
        
        // Split files expected from monocursor grouping:
        // IoInt is the max groupId (implicit primary file test_mono.bin)
        // IoDouble is the non-max groupId (split file test_mono.IoDouble.bin)
        val intGroupFilename = tempDir.resolve("test_mono.IoInt.bin").toString()

        // 1. Create a Cursor with columns of two different types
        val colA = RecordMeta("colA", IOMemento.IoInt)
        val colB = RecordMeta("colB", IOMemento.IoDouble)
        val colC = RecordMeta("colC", IOMemento.IoInt)
        val metaList = listOf(colA, colB, colC).toSeries()

        val rowValues = listOf(10, 3.14, 20).toSeries()
        val rowVec: RowVec = rowValues.size j { colIdx ->
            rowValues[colIdx] j { metaList[colIdx] }
        }
        val cursor: Cursor = listOf(rowVec).toSeries()

        // 2. Write with default builder (which defaults to useMonocursorGroupings = true)
        val isamDataWriter = isamDataFile {
            datafileFilename = dataFilename
        }
        
        // Write the data
        IsamDataFile.write(cursor, isamDataWriter.datafileFilename)

        // 3. Verify files on disk
        assertTrue(Files.exists(Paths.get(metaFilename)), "Meta file should exist")
        assertTrue(Files.exists(Paths.get(dataFilename)), "Default IoDouble file should exist")
        assertTrue(Files.exists(Paths.get(intGroupFilename)), "IoInt split file should exist")

        // 4. Read back using builder DSL
        val isamDataReader = isamDataFile {
            datafileFilename = dataFilename
        }
        isamDataReader.open()
        assertEquals(1, isamDataReader.size, "Should read 1 record")
        
        val row = isamDataReader.b(0)
        assertEquals(10, row[0].a, "colA should be 10")
        assertEquals(3.14, row[1].a, "colB should be 3.14")
        assertEquals(20, row[2].a, "colC should be 20")
        
        isamDataReader.close()
    }

    @Test
    fun testMonoCursorReifiedType() {
        val names = listOf("first", "second")
        val matrix = listOf(
            listOf("alpha", "beta").toSeries(),
            listOf("gamma", "delta").toSeries()
        ).toSeries()
        val monoCursor = MonoCursor(names.toSeries<CharSequence>(), IOMemento.IoString, matrix)

        assertEquals(2, monoCursor.size)
        val row0 = monoCursor.b(0)
        assertEquals(2, row0.size)
        assertEquals("alpha", row0[0].a)
        assertEquals("first", row0[0].b().name)
        assertEquals(IOMemento.IoString, row0[0].b().type)
        assertEquals("beta", row0[1].a)
        assertEquals("second", row0[1].b().name)

        val row1 = monoCursor.b(1)
        assertEquals("gamma", row1[0].a)
        assertEquals("delta", row1[1].a)
    }
}
