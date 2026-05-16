package borg.trikeshed.isam

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.file.Paths
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IsamDataFileTest {

    private fun rowOf(vararg pairs: Pair<Any?, IOMemento>): RowVec {
        val count = pairs.size
        val values: Series<Any?> = count j { ix: Int -> pairs[ix].first }
        val metas: Series<`ColumnMeta↻`> = count j { ix: Int ->
            { RecordMeta("col$ix", pairs[ix].second) }
        }
        return values j metas
    }

    @Test
    fun testAppendExisting() {
        val datafilename = "test_append.isam"
        val metafilename = "$datafilename.meta"
        val fileOps = JvmFileOperations()

        // Cleanup
        File(datafilename).delete()
        File(metafilename).delete()

        try {
            // 1. Initial write (via append)
            val row1 = rowOf(10 to IOMemento.IoInt, 20.0 to IOMemento.IoDouble)
            val row2 = rowOf(30 to IOMemento.IoInt, 40.0 to IOMemento.IoDouble)

            IsamDataFile.append(listOf(row1, row2), datafilename, emptyMap(), null, fileOps)

            assertEquals(true, fileOps.exists(datafilename))
            assertEquals(true, fileOps.exists(metafilename))

            val initialSize = Files.size(Paths.get(datafilename))
            assertEquals(24, initialSize.toInt()) // 2 rows * (4 for int + 8 for double)

            // 2. Append more records
            val row3 = rowOf(50 to IOMemento.IoInt, 60.0 to IOMemento.IoDouble)
            IsamDataFile.append(listOf(row3), datafilename, emptyMap(), null, fileOps)

            val finalSize = Files.size(Paths.get(datafilename))
            assertEquals(36, finalSize.toInt()) // 3 rows * 12 bytes

            // 3. Verify reading
            val metaReader = IsamMetaFileReader(metafilename, fileOps)
            val isamFile = IsamDataFile(datafilename, metafilename, metaReader, fileOps)
            isamFile.open()

            assertEquals(3, isamFile.a) // 3 records

            val readRow1 = isamFile.b(0)
            assertEquals(10, readRow1[0].a)
            assertEquals(20.0, readRow1[1].a)

            val readRow3 = isamFile.b(2)
            assertEquals(50, readRow3[0].a)
            assertEquals(60.0, readRow3[1].a)

            isamFile.close()

        } finally {
            File(datafilename).delete()
            File(metafilename).delete()
        }
    }

    @Test
    fun testSchemaMismatch() {
        val datafilename = "test_mismatch.isam"
        val metafilename = "$datafilename.meta"
        val fileOps = JvmFileOperations()

        File(datafilename).delete()
        File(metafilename).delete()

        try {
            assertFailsWith<IllegalArgumentException> {
                val row1 = rowOf(10 to IOMemento.IoInt)
                IsamDataFile.append(listOf(row1), datafilename, emptyMap(), null, fileOps)

                // Append with different schema
                val row2 = rowOf(20.0 to IOMemento.IoDouble)
                IsamDataFile.append(listOf(row2), datafilename, emptyMap(), null, fileOps)
            }
        } finally {
            File(datafilename).delete()
            File(metafilename).delete()
        }
    }
}
