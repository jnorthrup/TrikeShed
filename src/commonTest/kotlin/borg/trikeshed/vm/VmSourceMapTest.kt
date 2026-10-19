package borg.trikeshed.vm

import kotlin.test.*

class VmSourceMapTest {
    @Test fun bodyWindowExcludesAdaptersAndKeepsGeneratedEvidence() {
        val mapping = VmSourceMap(4, 3, 4)
        val generated = mapOf("line" to 5, "column" to 7, "lineStart" to 2, "lineCount" to 8,
            "sourceLines" to listOf("    import json", "    input = json.loads(input)", "    first", "      second", "    third", "end"),
            "breakpoint" to mapOf("hit" to true, "requestedLine" to 6, "resolvedLine" to 5))
        val observed = mapping.observation(generated)
        for ((key, value) in generated) assertEquals(value, observed[key], key)
        val authored = observed["authored"] as Map<*, *>
        assertEquals(4, authored["generatedStartLine"])
        assertEquals(3, authored["lineCount"])
        assertEquals(2, authored["line"])
        assertEquals(3, authored["column"])
        assertEquals(1, authored["lineStart"])
        assertEquals(listOf("first", "  second", "third"), authored["sourceLines"])
        assertEquals(mapOf("hit" to true, "requestedLine" to 3, "resolvedLine" to 2), authored["breakpoint"])
    }

    @Test fun adapterCoordinatesAreNotInventedAndWindowsRemainBounded() {
        val mapping = VmSourceMap(16, 20, 2)
        for (line in listOf(1, 15, 36, 100)) {
            val authored = mapping.observation(mapOf("line" to line, "column" to 1,
                "lineStart" to 1, "sourceLines" to listOf("def adapter", "  body"),
                "breakpoint" to mapOf("hit" to true, "requestedLine" to 1, "resolvedLine" to 15)))["authored"] as Map<*, *>
            assertNull(authored["line"])
            assertNull(authored["column"])
            assertNull(authored["lineStart"])
            assertEquals(emptyList<String>(), authored["sourceLines"])
            assertEquals(mapOf("hit" to true, "requestedLine" to null, "resolvedLine" to null), authored["breakpoint"])
        }
        val authored = mapping.observation(mapOf("line" to 17, "column" to 2,
            "lineStart" to 16, "sourceLines" to List(17) { "  " + "x".repeat(510) }, "truncated" to true))["authored"] as Map<*, *>
        assertNull(authored["column"], "Indentation is not authored text")
        assertEquals(17, (authored["sourceLines"] as List<*>).size)
        assertTrue((authored["sourceLines"] as List<*>).all { (it as String).length == 510 })
    }

    @Test fun sourceSpansRejectInvalidRanges() {
        assertFailsWith<IllegalArgumentException> { VmSourceMap(0, 1, 0) }
        assertFailsWith<IllegalArgumentException> { VmSourceMap(1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { VmSourceMap(1, 1, -1) }
        assertFailsWith<IllegalArgumentException> { VmSourceMap(Int.MAX_VALUE, 2, 0) }
        val mapping = VmSourceMap(2, 3, 0)
        assertNull(mapping.line(null))
        assertNull(mapping.line(1))
        assertEquals(1, mapping.line(2))
        assertEquals(3, mapping.line(4))
        assertNull(mapping.line(5))
    }
}
