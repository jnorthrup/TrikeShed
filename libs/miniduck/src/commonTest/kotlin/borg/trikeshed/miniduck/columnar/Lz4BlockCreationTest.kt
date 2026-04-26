package borg.trikeshed.miniduck.columnar

import borg.trikeshed.test.TODOError
import kotlin.test.*

class Lz4BlockCreationTest {
    @Test fun `Lz4Index creates aligned chunks with offset index`() {
        assertFailsWith<TODOError> {
            // Should compress in aligned chunks and record offsets
            throw TODOError("Lz4 block creation not implemented")
        }
    }
    
    @Test fun `Lz4Index chunk size affects compression ratio`() {
        assertFailsWith<TODOError> {
            // Different chunk sizes should produce different ratios
            throw TODOError("Lz4 chunk size optimization not implemented")
        }
    }
    
    @Test fun `Lz4Index handles partial final chunks`() {
        assertFailsWith<TODOError> {
            // Should handle non-aligned final chunks
            throw TODOError("Lz4 partial chunk handling not implemented")
        }
    }
}
