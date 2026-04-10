package borg.literbike.ccek.json

/**
 * JSON Error types with position tracking
 *
 * Provides detailed error information for JSON parsing failures,
 * compatible with Bun's error reporting format.
 */

/** JSON parsing error */
sealed class JsonError : Exception() {
    /** Syntax error at specific position */
    data class Syntax(
        val message: String,
        val line: Int,
        val column: Int,
        val offset: Int,
    ) : JsonError()

    /** Invalid number format */
    data class InvalidNumber(
        val value: String,
        val line: Int,
        val column: Int,
        val offset: Int,
    ) : JsonError()

    /** Unterminated string */
    data class UnterminatedString(
        val line: Int,
        val column: Int,
        val offset: Int,
    ) : JsonError()

    /** Unexpected character */
    data class UnexpectedCharacter(
        val character: Char,
        val expected: String,
        val line: Int,
        val column: Int,
        val offset: Int,
    ) : JsonError()

    /** Trailing data after JSON value */
    data class TrailingData(val offset: Int) : JsonError()

    /** Duplicate key in object (JSON5 spec violation) */
    data class DuplicateKey(
        val key: String,
        val line: Int,
        val column: Int,
        val offset: Int,
    ) : JsonError()

    /** Invalid escape sequence */
    data class InvalidEscape(
        val sequence: String,
        val line: Int,
        val column: Int,
        val offset: Int,
    ) : JsonError()

    /** Stack overflow (deeply nested structures) */
    data object StackOverflow : JsonError()

    /** Out of memory */
    data object OutOfMemory : JsonError()

    /** IO error */
    data class Io(val message: String) : JsonError()

    /** Get the line number where the error occurred */
    fun line(): Int? = when (this) {
        is Syntax -> line
        is InvalidNumber -> line
        is UnterminatedString -> line
        is UnexpectedCharacter -> line
        is DuplicateKey -> line
        is InvalidEscape -> line
        else -> null
    }

    /** Get the column number where the error occurred */
    fun column(): Int? = when (this) {
        is Syntax -> column
        is InvalidNumber -> column
        is UnterminatedString -> column
        is UnexpectedCharacter -> column
        is DuplicateKey -> column
        is InvalidEscape -> column
        else -> null
    }

    /** Get the byte offset where the error occurred */
    fun offset(): Int? = when (this) {
        is Syntax -> offset
        is InvalidNumber -> offset
        is UnterminatedString -> offset
        is UnexpectedCharacter -> offset
        is TrailingData -> offset
        is DuplicateKey -> offset
        is InvalidEscape -> offset
        else -> null
    }

    override val message: String
        get() = when (this) {
            is Syntax -> "Syntax error at line $line, column $column: $message"
            is InvalidNumber -> "Invalid number '$value' at line $line, column $column"
            is UnterminatedString -> "Unterminated string at line $line, column $column"
            is UnexpectedCharacter -> "Unexpected character '$character' at line $line, column $column (expected $expected)"
            is TrailingData -> "Trailing data after JSON value at offset $offset"
            is DuplicateKey -> "Duplicate key '$key' at line $line, column $column"
            is InvalidEscape -> "Invalid escape sequence '$sequence' at line $line, column $column"
            StackOverflow -> "Stack overflow: JSON structure too deeply nested"
            OutOfMemory -> "Out of memory"
            is Io -> "IO error: $message"
        }

    companion object {
        /** Create a syntax error at a specific position */
        fun syntax(message: String, line: Int, column: Int, offset: Int): JsonError {
            return Syntax(message, line, column, offset)
        }

        /** Create an invalid number error */
        fun invalidNumber(value: String, line: Int, column: Int, offset: Int): JsonError {
            return InvalidNumber(value, line, column, offset)
        }

        /** Create an unterminated string error */
        fun unterminatedString(line: Int, column: Int, offset: Int): JsonError {
            return UnterminatedString(line, column, offset)
        }

        /** Create an unexpected character error */
        fun unexpectedCharacter(
            character: Char,
            expected: String,
            line: Int,
            column: Int,
            offset: Int,
        ): JsonError {
            return UnexpectedCharacter(character, expected, line, column, offset)
        }

        /** Create a trailing data error */
        fun trailingData(offset: Int): JsonError {
            return TrailingData(offset)
        }

        /** Create a duplicate key error */
        fun duplicateKey(key: String, line: Int, column: Int, offset: Int): JsonError {
            return DuplicateKey(key, line, column, offset)
        }

        /** Create an invalid escape error */
        fun invalidEscape(sequence: String, line: Int, column: Int, offset: Int): JsonError {
            return InvalidEscape(sequence, line, column, offset)
        }
    }
}
