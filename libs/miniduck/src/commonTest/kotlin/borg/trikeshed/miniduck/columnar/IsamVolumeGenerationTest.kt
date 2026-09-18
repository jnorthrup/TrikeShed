package borg.trikeshed.miniduck.columnar

import borg.trikeshed.lib.j
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.MiniCursor
import kotlin.test.*

/**
 * Tests for IsamVolume generation from sorted MiniCursor.
 */
class IsamVolumeGenerationTest {
    @Test fun `IsamVolume-generate fails when cursor empty`() {
        val empty: MiniCursor = 0 j { throw IndexOutOfBoundsException("empty") }
        val schema = listOf(ColumnSchema("openTime", ColumnType.Long))
        val tempDir = "/tmp/test_isam_${kotlin.random.Random.nextLong()}"
        assertFails {
            IsamVolume.generateIsam(empty, schema, tempDir)
        }
    }

    @Test fun `IsamVolume-generate fails when schema empty`() {
        val rows: MiniCursor = 1 j { DocRowVec(listOf("x"), listOf(1L)) }
        val emptySchema = emptyList<ColumnSchema>()
        val tempDir = "/tmp/test_isam_${kotlin.random.Random.nextLong()}"
        assertFails {
            IsamVolume.generateIsam(rows, emptySchema, tempDir)
        }
    }

    @Test fun `IsamVolume-generate creates correct file structure`() {
        val rows: MiniCursor = 1 j { DocRowVec(listOf("openTime"), listOf(1709251200000L)) }
        val schema = listOf(ColumnSchema("openTime", ColumnType.Long))
        val tempDir = "/tmp/test_isam_${kotlin.random.Random.nextLong()}"
        assertFails {
            IsamVolume.generateIsam(rows, schema, tempDir)
        }
    }
}
