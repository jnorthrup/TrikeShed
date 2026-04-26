package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class IsamVolumeGenerationTest {
    @Test fun `IsamVolume-generate fails when rows empty`() {
        assertFails {
            generateIsam(emptyList(), emptyList())
        }
    }

    @Test fun `IsamVolume-generate fails when schema empty`() {
        assertFails {
            generateIsam(listOf(mapOf("x" to 1L)), emptyList())
        }
    }

    @Test fun `IsamVolume-generate creates correct file structure`() {
        assertFails {
            generateIsam(listOf(mapOf("x" to 1L)), listOf(ColumnSchema("x", ColumnType.Long)))
        }
    }
}
