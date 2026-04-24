package borg.trikeshed.couch.miniduck.columnar

import kotlin.test.*

class ClusterLayoutDefaultsTest {
    @Test fun `ClusterLayout defaults metadataVersion to 1`() {
        val schema = listOf(ColumnSchema("a", ColumnType.Long))
        val layout = ClusterLayout("/tmp/test", schema)
        assertEquals(1, layout.metadataVersion)
    }

    @Test fun `ClusterLayout preserves provided metadataVersion`() {
        val schema = listOf(ColumnSchema("a", ColumnType.Long))
        val layout = ClusterLayout("/tmp/test", schema, 42)
        assertEquals(42, layout.metadataVersion)
    }
}
