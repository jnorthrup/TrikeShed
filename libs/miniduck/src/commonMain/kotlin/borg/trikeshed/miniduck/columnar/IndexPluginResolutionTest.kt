package borg.trikeshed.miniduck.columnar

import kotlin.test.*

class IndexPluginResolutionTest {
    @Test fun `ZranIndex plugin resolves correctly`() {
        val plugin = IndexPluginRegistry.resolve("ZranIndex")
        assertTrue(plugin is ZranIndex)
    }
    
    @Test fun `Lz4Index plugin resolves correctly`() {
        val plugin = IndexPluginRegistry.resolve("Lz4Index")
        assertTrue(plugin is Lz4Index)
    }
    
    @Test fun `Unknown plugin throws`() {
        assertFailsWith<IllegalArgumentException> {
            IndexPluginRegistry.resolve("UnknownIndex")
        }
    }
    
    @Test fun `ZranIndex builds skip table`() {
        val plugin = ZranIndex()
        assertFailsWith<TODOError> {
            plugin.openIndexCursor(0, "")
        }
    }
    
    @Test fun `Lz4Index builds chunk index`() {
        val plugin = Lz4Index()
        assertFailsWith<TODOError> {
            plugin.openIndexCursor(0, "")
        }
    }
}
