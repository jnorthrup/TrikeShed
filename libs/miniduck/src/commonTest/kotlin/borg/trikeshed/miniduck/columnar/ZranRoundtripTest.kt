package borg.trikeshed.miniduck.columnar

import borg.trikeshed.test.TODOError
import kotlin.test.*

class ZranRoundtripTest {
    @Test fun `ZranIndex roundtrip decompresses and recompresses`() {
        assertFailsWith<TODOError> {
            // Zran should: decompress entire block > seek > recompress
            throw TODOError("Zran roundtrip not implemented")
        }
    }

    @Test fun `ZranIndex skip table enables efficient seeking`() {
        assertFailsWith<TODOError> {
            // Skip table should allow O(log n) seeking
            throw TODOError("Zran skip table not implemented")
        }
    }

    @Test fun `ZranIndex handles block boundaries`() {
        assertFailsWith<TODOError> {
            // Should handle crossing block boundaries
            throw TODOError("Zran block boundary handling not implemented")
        }
    }
}
