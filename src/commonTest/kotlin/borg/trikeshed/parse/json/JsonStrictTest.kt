package borg.trikeshed.parse.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class JsonStrictTest {
    @Test
    fun retainsJsonEscapesNumbersAndNestedValues() {
        val source = """{"x":"\u0041\/\b\f\n\r\t\"\\","n":-1.25e+2,"a":[true,false,null,{}]}"""
        val value = JsonSupport.parseStrict(source) as Map<*, *>
        assertEquals("A/\b\u000c\n\r\t\"\\", value["x"])
        assertEquals(-125.0, value["n"])
        assertEquals(listOf(true, false, null, emptyMap<String, Any?>()), value["a"])
        assertEquals(value, JsonSupport.parseStrict(JsonSupport.stringify(value)))
        assertEquals(true, JsonSupport.parseStrict(" \ttrue\r\n"))
        assertNull(JsonSupport.parseStrict("null"))
    }

    @Test
    fun rejectsIncompleteAndAmbiguousSyntax() {
        val invalid = listOf(
            "", " ", "{} trailing", "{}[]", "[1,]", "[1 2]", "{\"a\":1,}",
            "{\"a\" 1}", "{a:1}", "{\"a\":}", "{\"a\":1]", "[1}", "[",
            "{\"a\":1", "trueish", "false0", "nil", "+1", "01", "-01", "-", ".1",
            "1.", "1e", "1e+", "1e-", "NaN", "Infinity", "\u000btrue",
            "\"unterminated", "\"\\q\"", "\"\\u12\"", "\"\\uZZZZ\"", "\"raw\nnewline\"",
        )
        for (source in invalid) assertFails("Accepted invalid JSON: $source") { JsonSupport.parseStrict(source) }
    }

    @Test
    fun boundsRecursiveNesting() {
        assertFails { JsonSupport.parseStrict("[".repeat(258) + "0" + "]".repeat(258)) }
    }
}
