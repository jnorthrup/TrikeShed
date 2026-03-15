package borg.trikeshed.parse.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsonScannerContractTest {
    @Test
    fun parseSimpleKeyValuePair() {
        val input = "\"name\":\"value\""

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.STRING, result!!.type)
        assertEquals("value", result.value)
    }

    @Test
    fun parseNumberToken() {
        val input = "\"count\":42"

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.NUMBER, result!!.type)
        assertEquals("42", result.value)
    }

    @Test
    fun parseBooleanTrueToken() {
        val input = "\"flag\":true"

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.BOOLEAN, result!!.type)
        assertEquals("true", result.value)
    }

    @Test
    fun parseBooleanFalseToken() {
        val input = "\"active\":false"

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.BOOLEAN, result!!.type)
        assertEquals("false", result.value)
    }

    @Test
    fun parseNullToken() {
        val input = "\"data\":null"

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.NULL, result!!.type)
        assertEquals("null", result.value)
    }

    @Test
    fun parseStringWithEscapeCharacters() {
        val input = "\"text\":\"line1\\nline2\""

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.STRING, result!!.type)
        assertEquals("line1\\nline2", result.value)
    }

    @Test
    fun parseNegativeNumber() {
        val input = "\"temperature\":-15"

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.NUMBER, result!!.type)
        assertEquals("-15", result.value)
    }

    @Test
    fun parseDecimalNumber() {
        val input = "\"price\":19.99"

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.NUMBER, result!!.type)
        assertEquals("19.99", result.value)
    }

    @Test
    fun parseScientificNotation() {
        val input = "\"value\":1.23e10"

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.NUMBER, result!!.type)
        assertEquals("1.23e10", result.value)
    }

    @Test
    fun parseInvalidInputReturnsNull() {
        val result = parseJsonToken("invalid")
        assertNull(result)
    }

    @Test
    fun parseEmptyInputReturnsNull() {
        val result = parseJsonToken("")
        assertNull(result)
    }

    @Test
    fun parseInputWithoutColonReturnsNull() {
        val result = parseJsonToken("\"key\"\"value\"")
        assertNull(result)
    }

    @Test
    fun parseInputWithMissingValueReturnsNull() {
        val result = parseJsonToken("\"key\":")
        assertNull(result)
    }

    @Test
    fun parseInputWithWhitespaceHandling() {
        val input = "   \"name\"   :   \"value\"   "

        val result = parseJsonToken(input)

        assertNotNull(result)
        assertEquals(JsonTokenType.STRING, result!!.type)
        assertEquals("value", result.value)
    }
}

data class JsonToken(
    val type: JsonTokenType,
    val value: String,
)

enum class JsonTokenType { STRING, NUMBER, BOOLEAN, NULL }

fun parseJsonToken(input: String): JsonToken? = TODO()
