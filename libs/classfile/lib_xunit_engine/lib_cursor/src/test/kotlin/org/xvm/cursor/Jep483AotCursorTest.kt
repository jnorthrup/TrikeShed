package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Jep483AotCursorTest {
    @Test
    fun `parses AOT classlist dump into blackboard entries with real features decoded`() {
        val dump = """
            # NOTE: Do not modify this file.
            java/lang/Object id: 0
        """.trimIndent()
        
        val entries = Jep483AotCursor.parseBlackboard(dump)
        assertEquals(1, entries.size)
        assertEquals("JEP483_AOT_DUMP", entries[0].provenance)
        
        // Assert that the real features (like methods) were extracted
        val docSeries = entries[0].doc.b as borg.trikeshed.lib.Series<Byte>
        val docBytes = ByteArray(docSeries.a) { i -> docSeries.b(i) }
        val docString = docBytes.decodeToString()
        assertTrue(docString.contains("method: hashCode"), "docString was: $docString")
    }
}
