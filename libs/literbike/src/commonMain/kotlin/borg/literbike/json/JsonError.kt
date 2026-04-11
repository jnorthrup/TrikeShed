package borg.literbike.json

/**
 * JSON error types with position tracking.
 * Ported from literbike/src/json/error.rs.
 *
 * Provides detailed error information for JSON parsing failures,
 * compatible with Bun's error reporting format.
 */
sealed class JsonError : Exception() {

    data class Syntax(
        val message: String,
        val line: Int,
        val column: Int,
        val offset: Int
    ) : JsonError()

    data class InvalidNumber(
        val value: String,
        val line: Int,
        val column: Int,
        val offset: Int
    ) : JsonError()

    data class UnterminatedString(
        val line: Int,
        val column: Int,
        val offset: Int
    ) : JsonError()

    data class UnexpectedCharacter(
        val character: Char,
        val expected: String,
        val line: Int,
        val column: Int,
        val offset: Int
    ) : JsonError()

    data class TrailingData(val offset: Int) : JsonError()

    data class DuplicateKey(
        val key: String,
        val line: Int,
        val column: Int,
        val offset: Int
    ) : JsonError()

    data class InvalidEscape(
        val sequence: String,
        val line: Int,
        val column: Int,
        val offset: Int
    ) : JsonError()

    data object StackOverflow : JsonError()

    data object OutOfMemory : JsonError()

    data class IoError(val message: String) : JsonError()

    fun line(): Int? = when (this) {
        is Syntax -> line
        is InvalidNumber -> line
        is UnterminatedString -> line
        is UnexpectedCharacter -> line
        is DuplicateKey -> line
        is InvalidEscape -> line
        else -> null
    }

    fun column(): Int? = when (this) {
        is Syntax -> column
        is InvalidNumber -> column
        is UnterminatedString -> column
        is UnexpectedCharacter -> column
        is DuplicateKey -> column
        is InvalidEscape -> column
        else -> null
    }

    fun offset(): Int? = when (this) {
        is Syntax -> offset
        is InvalidNumber -> offset
        is UnterminatedString -> offset
        is UnexpectedCharacter -> offset
        is DuplicateKey -> offset
        is InvalidEscape -> offset
        is TrailingData -> offset
        else -> null
    }

    companion object {
        fun syntax(message: String, line: Int, column: Int, offset: Int): JsonError =
            Syntax(message, line, column, offset)

        fun invalidNumber(value: String, line: Int, column: Int, offset: Int): JsonError =
            InvalidNumber(value, line, column, offset)

        fun unterminatedString(line: Int, column: Int, offset: Int): JsonError =
            UnterminatedString(line, column, offset)

        fun unexpectedCharacter(
            character: Char,
            expected: String,
            line: Int,
            column: Int,
            offset: Int
        ): JsonError =
            UnexpectedCharacter(character, expected, line, column, offset)

        fun trailingData(offset: Int): JsonError =
            TrailingData(offset)

        fun duplicateKey(key: String, line: Int, column: Int, offset: Int): JsonError =
            DuplicateKey(key, line, column, offset)

        fun invalidEscape(sequence: String, line: Int, column: Int, offset: Int): JsonError =
            InvalidEscape(sequence, line, column, offset)
    }

    override fun toString(): String = when (this) {
        is Syntax -> "Syntax error at line $line, column $column: $message"
        is InvalidNumber -> "Invalid number '$value' at line $line, column $column"
        is UnterminatedString -> "Unterminated string at line $line, column $column"
        is UnexpectedCharacter -> "Unexpected character '$character' at line $line, column $column (expected $expected)"
        is TrailingData -> "Trailing data after JSON value at offset $offset"
        is DuplicateKey -> "Duplicate key '$key' at line $line, column $column"
        is InvalidEscape -> "Invalid escape sequence '$sequence' at line $line, column $column"
        StackOverflow -> "Stack overflow: JSON structure too deeply nested"
        OutOfMemory -> "Out of memory"
        is IoError -> "IO error: $message"
    }
}
