package borg.literbike.rbcursive.protocols

import borg.literbike.rbcursive.*

/**
 * JSON parser using RBCursive combinators (for PAC files).
 * SIMD-accelerated JSON parsing for proxy auto-configuration.
 * Ported from literbike/src/rbcursive/protocols/json.rs.
 */

/**
 * JSON value types.
 */
sealed class JsonValue {
    data class StringValue(val value: ByteArray) : JsonValue() {
        override fun equals(other: Any?): Boolean =
            other is StringValue && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
    data class NumberValue(val value: ByteArray) : JsonValue() {
        override fun equals(other: Any?): Boolean =
            other is NumberValue && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
    data class BooleanValue(val value: Boolean) : JsonValue()
    object NullValue : JsonValue()
    data class ObjectValue(val value: JsonObject) : JsonValue()
    data class ArrayValue(val value: JsonArray) : JsonValue()
}

/**
 * JSON object.
 */
data class JsonObject(
    val pairs: MutableList<JsonPair> = mutableListOf()
)

/**
 * JSON key-value pair.
 */
data class JsonPair(
    val key: ByteArray,
    val value: JsonValue
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JsonPair) return false
        return key.contentEquals(other.key) && value == other.value
    }
    override fun hashCode(): Int {
        var result = key.contentHashCode()
        result = 31 * result + value.hashCode()
        return result
    }
}

/**
 * JSON array.
 */
data class JsonArray(
    val values: MutableList<JsonValue> = mutableListOf()
)

/**
 * JSON parser for PAC (Proxy Auto-Configuration) files.
 */
class JsonParser(
    private val scanner: SimdScanner = ScalarScanner()
) {
    companion object {
        fun new(): JsonParser = JsonParser()
    }

    /** Parse JSON value */
    fun parseValue(input: ByteArray): ParseResult<JsonValue> {
        return skipWhitespaceAndParse(input)
    }

    /** Parse JSON object */
    fun parseObject(input: ByteArray): ParseResult<JsonObject> {
        var input = input
        var consumed = 0

        // Skip whitespace
        val (remaining, wsConsumed) = skipWhitespace(input)
        input = remaining
        consumed += wsConsumed

        // Expect opening brace
        if (input.isEmpty() || input[0] != '{'.code.toByte()) {
            return ParseResult.Error(ParseError.InvalidInput, consumed)
        }
        input = input.copyOfRange(1, input.size)
        consumed += 1

        val pairs = mutableListOf<JsonPair>()

        // Skip whitespace after opening brace
        val (rem2, ws2) = skipWhitespace(input)
        input = rem2
        consumed += ws2

        // Check for empty object
        if (input.isNotEmpty() && input[0] == '}'.code.toByte()) {
            return ParseResult.Complete(JsonObject(pairs), consumed + 1)
        }

        while (true) {
            // Skip whitespace before parsing next key
            val (rem3, ws3) = skipWhitespace(input)
            input = rem3
            consumed += ws3

            // Parse key (must be string)
            val (key, keyConsumed) = when (val result = parseString(input)) {
                is ParseResult.Complete -> result.value to result.consumed
                is ParseResult.Incomplete -> return result
                is ParseResult.Error -> return result
            }
            input = input.copyOfRange(keyConsumed, input.size)
            consumed += keyConsumed

            // Skip whitespace and expect colon
            val (rem4, ws4) = skipWhitespace(input)
            input = rem4
            consumed += ws4

            if (input.isEmpty() || input[0] != ':'.code.toByte()) {
                return ParseResult.Error(ParseError.InvalidInput, consumed)
            }
            input = input.copyOfRange(1, input.size)
            consumed += 1

            // Skip whitespace after colon
            val (rem5, ws5) = skipWhitespace(input)
            input = rem5
            consumed += ws5

            // Parse value
            val (value, valueConsumed) = when (val result = parseValue(input)) {
                is ParseResult.Complete -> result.value to result.consumed
                is ParseResult.Incomplete -> return result
                is ParseResult.Error -> return result
            }
            input = input.copyOfRange(valueConsumed, input.size)
            consumed += valueConsumed

            pairs.add(JsonPair(key, value))

            // Skip whitespace and check for comma or closing brace
            val (rem6, ws6) = skipWhitespace(input)
            input = rem6
            consumed += ws6

            if (input.isEmpty()) {
                return ParseResult.Incomplete(consumed)
            }

            when (input[0]) {
                ','.code.toByte() -> {
                    input = input.copyOfRange(1, input.size)
                    consumed += 1
                }
                '}'.code.toByte() -> {
                    return ParseResult.Complete(JsonObject(pairs), consumed + 1)
                }
                else -> {
                    return ParseResult.Error(ParseError.InvalidInput, consumed)
                }
            }
        }
    }

    /** Parse JSON string using SIMD for quote detection */
    fun parseString(input: ByteArray): ParseResult<ByteArray> {
        if (input.isEmpty() || input[0] != '"'.code.toByte()) {
            return ParseResult.Error(ParseError.InvalidInput, 0)
        }

        // Use SIMD to find all quote positions
        val quotePositions = scanner.scanQuotes(input)

        // First quote should be at position 0
        if (quotePositions.isEmpty() || quotePositions[0] != 0) {
            return ParseResult.Error(ParseError.InvalidInput, 0)
        }

        // Find closing quote, handling escapes
        for (i in 1 until quotePositions.size) {
            val quotePos = quotePositions[i]
            if (quotePos >= input.size) continue

            // Check if this quote is escaped
            var backslashes = 0
            var idx = quotePos - 1
            while (idx >= 1 && input[idx] == '\\'.code.toByte()) {
                backslashes++
                idx--
            }

            // If odd number of backslashes, the quote is escaped
            val escaped = backslashes % 2 == 1
            if (!escaped) {
                val stringContent = input.copyOfRange(1, quotePos)
                return ParseResult.Complete(stringContent, quotePos + 1)
            }
        }

        // No closing quote found
        return ParseResult.Incomplete(input.size)
    }

    /** Parse JSON array */
    fun parseArray(input: ByteArray): ParseResult<JsonArray> {
        var input = input
        var consumed = 0

        // Skip whitespace
        val (remaining, wsConsumed) = skipWhitespace(input)
        input = remaining
        consumed += wsConsumed

        // Expect opening bracket
        if (input.isEmpty() || input[0] != '['.code.toByte()) {
            return ParseResult.Error(ParseError.InvalidInput, consumed)
        }
        input = input.copyOfRange(1, input.size)
        consumed += 1

        val values = mutableListOf<JsonValue>()

        // Skip whitespace after opening bracket
        val (rem2, ws2) = skipWhitespace(input)
        input = rem2
        consumed += ws2

        // Check for empty array
        if (input.isNotEmpty() && input[0] == ']'.code.toByte()) {
            return ParseResult.Complete(JsonArray(values), consumed + 1)
        }

        while (true) {
            // Parse value
            val (value, valueConsumed) = when (val result = parseValue(input)) {
                is ParseResult.Complete -> result.value to result.consumed
                is ParseResult.Incomplete -> return result
                is ParseResult.Error -> return result
            }
            input = input.copyOfRange(valueConsumed, input.size)
            consumed += valueConsumed

            values.add(value)

            // Skip whitespace and check for comma or closing bracket
            val (rem3, ws3) = skipWhitespace(input)
            input = rem3
            consumed += ws3

            if (input.isEmpty()) {
                return ParseResult.Incomplete(consumed)
            }

            when (input[0]) {
                ','.code.toByte() -> {
                    input = input.copyOfRange(1, input.size)
                    consumed += 1
                }
                ']'.code.toByte() -> {
                    return ParseResult.Complete(JsonArray(values), consumed + 1)
                }
                else -> {
                    return ParseResult.Error(ParseError.InvalidInput, consumed)
                }
            }
        }
    }

    /** Skip whitespace using SIMD acceleration */
    private fun skipWhitespace(input: ByteArray): Pair<ByteArray, Int> {
        var consumed = 0
        while (consumed < input.size) {
            when (input[consumed]) {
                ' '.code.toByte(), '\t'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte() -> consumed++
                else -> break
            }
        }
        return input.copyOfRange(consumed, input.size) to consumed
    }

    /** Skip whitespace and parse value */
    private fun skipWhitespaceAndParse(input: ByteArray): ParseResult<JsonValue> {
        val (input2, consumed) = skipWhitespace(input)

        if (input2.isEmpty()) {
            return ParseResult.Incomplete(consumed)
        }

        val result = when (input2[0]) {
            '"'.code.toByte() -> {
                when (val r = parseString(input2)) {
                    is ParseResult.Complete -> ParseResult.Complete(JsonValue.StringValue(r.value), r.consumed)
                    is ParseResult.Incomplete -> r
                    is ParseResult.Error -> r
                }
            }
            '{'.code.toByte() -> {
                when (val r = parseObject(input2)) {
                    is ParseResult.Complete -> ParseResult.Complete(JsonValue.ObjectValue(r.value), r.consumed)
                    is ParseResult.Incomplete -> r
                    is ParseResult.Error -> r
                }
            }
            '['.code.toByte() -> {
                when (val r = parseArray(input2)) {
                    is ParseResult.Complete -> ParseResult.Complete(JsonValue.ArrayValue(r.value), r.consumed)
                    is ParseResult.Incomplete -> r
                    is ParseResult.Error -> r
                }
            }
            't'.code.toByte() -> {
                if (input2.size >= 4 && input2.copyOf(4).contentEquals("true".toByteArray())) {
                    ParseResult.Complete(JsonValue.BooleanValue(true), 4)
                } else {
                    ParseResult.Error(ParseError.InvalidInput, 0)
                }
            }
            'f'.code.toByte() -> {
                if (input2.size >= 5 && input2.copyOf(5).contentEquals("false".toByteArray())) {
                    ParseResult.Complete(JsonValue.BooleanValue(false), 5)
                } else {
                    ParseResult.Error(ParseError.InvalidInput, 0)
                }
            }
            'n'.code.toByte() -> {
                if (input2.size >= 4 && input2.copyOf(4).contentEquals("null".toByteArray())) {
                    ParseResult.Complete(JsonValue.NullValue, 4)
                } else {
                    ParseResult.Error(ParseError.InvalidInput, 0)
                }
            }
            '-'.code.toByte(), in '0'.code.toByte()..'9'.code.toByte() -> {
                parseNumber(input2)
            }
            else -> ParseResult.Error(ParseError.InvalidInput, 0)
        }

        // Add consumed whitespace to result
        return when (result) {
            is ParseResult.Complete -> ParseResult.Complete(result.value, consumed + result.consumed)
            is ParseResult.Incomplete -> ParseResult.Incomplete(consumed + result.consumed)
            is ParseResult.Error -> ParseResult.Error(result.error, consumed + result.consumed)
        }
    }

    /** Parse JSON number (simplified) */
    private fun parseNumber(input: ByteArray): ParseResult<JsonValue> {
        var consumed = 0

        // Optional minus
        if (consumed < input.size && input[consumed] == '-'.code.toByte()) {
            consumed++
        }

        if (consumed >= input.size) {
            return ParseResult.Incomplete(consumed)
        }

        // Digits
        if (input[consumed] !in '0'.code.toByte()..'9'.code.toByte()) {
            return ParseResult.Error(ParseError.InvalidInput, consumed)
        }

        while (consumed < input.size && input[consumed] in '0'.code.toByte()..'9'.code.toByte()) {
            consumed++
        }

        // Optional decimal part
        if (consumed < input.size && input[consumed] == '.'.code.toByte()) {
            consumed++
            if (consumed >= input.size || input[consumed] !in '0'.code.toByte()..'9'.code.toByte()) {
                return ParseResult.Error(ParseError.InvalidInput, consumed)
            }
            while (consumed < input.size && input[consumed] in '0'.code.toByte()..'9'.code.toByte()) {
                consumed++
            }
        }

        return ParseResult.Complete(JsonValue.NumberValue(input.copyOf(consumed)), consumed)
    }
}
