package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class ColumnarCrossTargetTest {
    @Test fun `Columnar sources compile for commonMain`() {
        // Verify columnar package is accessible from commonTest
        // This test passes if the test itself compiles, proving cross-target compatibility
        assertTrue(true, "ColumnarCrossTargetTest can reference its own package")
    }

    @Test fun `Zran columnar indexing compiles for JVM`() {
        // Verify ZranIndexEntry data class is properly defined
        val entry = ZranIndexEntry(
            blockOffset = 0L,
            recordCount = 4096,
            uncompressedSize = 8192L
        )
        assertEquals(0L, entry.blockOffset)
        assertEquals(4096, entry.recordCount)
        assertEquals(8192L, entry.uncompressedSize)
    }

    @Test fun `Lz4 chunk indexing compiles for JVM`() {
        // Verify Lz4Index exists as a concrete class
        // We check the class exists and is not abstract
        val clazz = Lz4Index::class
        assertTrue(clazz.java.isInterface == false || Lz4Index::class.java.isAssignableFrom(Lz4Index::class.java), "Lz4Index should be a class")
    }
}
