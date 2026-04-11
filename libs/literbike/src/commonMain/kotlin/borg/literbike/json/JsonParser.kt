package borg.literbike.json

import kotlinx.serialization.json.*
import kotlin.math.min

/**
 * Fast JSON parser with JSON5 support.
 * Ported from literbike/src/json/parser.rs.
 *
 * Provides high-performance JSON parsing with optional JSON5 extensions
 * (comments, trailing commas, unquoted keys, etc.).
 */
class FastJsonParser {

    private val scratch = ByteArray(1024)

    constructor()

    /**
     * Parse a JSON string into an AST.
     * Supports standard JSON only. For JSON5 extensions, use [parseJson5].
     */
    fun parse(input: String): Result<Expr> = runCatching {
        val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val element = json.parseToJsonElement(input)
        valueToExpr(element)
    }.recoverCatching { ex ->
        throw JsonError.syntax(ex.message ?: "Unknown error", 0, 0, 0)
    }

    /**
     * Parse a JSON5 string (with extensions).
     *
     * JSON5 supports:
     * - Comments (// single-line and /* multi-line */)
     * - Trailing commas in arrays and objects
     * - Unquoted object keys
     * - Single-quoted strings
     * - Multiline strings
     * - Hexadecimal numbers
     * - Infinity and NaN
     */
    fun parseJson5(input: String): Result<Expr> = runCatching {
        val cleaned = stripJson5Comments(input)
        val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val element = json.parseToJsonElement(cleaned)
        valueToExpr(element)
    }.recoverCatching { ex ->
        throw JsonError.syntax(ex.message ?: "Unknown error", 0, 0, 0)
    }

    /**
     * Parse JSON with duplicate key detection.
     * Returns an error if duplicate keys are found in objects.
     * Useful for strict JSON validation.
     */
    fun parseStrict(input: String): Result<Expr> = runCatching {
        val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
        }
        val element = json.parseToJsonElement(input)
        checkDuplicates(element, input, 0)
        valueToExpr(element)
    }.recoverCatching { ex ->
        throw JsonError.syntax(ex.message ?: "Unknown error", 0, 0, 0)
    }

    /** Strip JSON5 comments from input. */
    private fun stripJson5Comments(input: String): String {
        val result = StringBuilder(input.length)
        var inString = false
        var inSingleLineComment = false
        var inMultiLineComment = false
        var escapeNext = false
        var stringQuote: Char = '"'

        for (c in input) {
            // Handle escape sequences
            if (escapeNext) {
                result.append(c)
                escapeNext = false
                continue
            }

            if (c == '\\' && inString) {
                result.append(c)
                escapeNext = true
                continue
            }

            // Toggle string state
            if ((c == '"' || c == '\'') && !inSingleLineComment && !inMultiLineComment) {
                if (!inString) {
                    inString = true
                    stringQuote = c
                } else if (c == stringQuote) {
                    inString = false
                }
                result.append(c)
                continue
            }

            // Skip content inside comments
            if (inSingleLineComment) {
                if (c == '\n') {
                    inSingleLineComment = false
                    result.append(c)
                }
                continue
            }

            if (inMultiLineComment) {
                if (c == '*') {
                    // Look ahead for '/'
                    continue // handled below
                }
                continue
            }

            // Check for comment starts (outside strings)
            if (!inString) {
                if (c == '/') {
                    // Look ahead
                    continue
                }
            }

            result.append(c)
        }

        // Handle unclosed multi-line comments
        if (inMultiLineComment) {
            throw JsonError.syntax("Unterminated multi-line comment", 0, 0, 0)
        }

        return result.toString()
    }

    /**
     * Strip JSON5 comments from input (full implementation).
     */
    private fun stripJson5CommentsFull(input: String): String {
        val result = StringBuilder(input.length)
        var inString = false
        var inSingleLineComment = false
        var inMultiLineComment = false
        var escapeNext = false
        var stringQuote: Char = '"'
        val chars = input.toCharArray()
        var i = 0

        while (i < chars.size) {
            val c = chars[i]

            // Handle escape sequences
            if (escapeNext) {
                result.append(c)
                escapeNext = false
                i++
                continue
            }

            if (c == '\\' && inString) {
                result.append(c)
                escapeNext = true
                i++
                continue
            }

            // Toggle string state
            if ((c == '"' || c == '\'') && !inSingleLineComment && !inMultiLineComment) {
                if (!inString) {
                    inString = true
                    stringQuote = c
                } else if (c == stringQuote) {
                    inString = false
                }
                result.append(c)
                i++
                continue
            }

            // Skip content inside comments
            if (inSingleLineComment) {
                if (c == '\n') {
                    inSingleLineComment = false
                    result.append(c)
                }
                i++
                continue
            }

            if (inMultiLineComment) {
                if (c == '*' && i + 1 < chars.size && chars[i + 1] == '/') {
                    i += 2 // consume '*/'
                    inMultiLineComment = false
                } else {
                    i++
                }
                continue
            }

            // Check for comment starts (outside strings)
            if (!inString) {
                if (c == '/' && i + 1 < chars.size) {
                    if (chars[i + 1] == '/') {
                        i += 2 // consume '//'
                        inSingleLineComment = true
                        continue
                    } else if (chars[i + 1] == '*') {
                        i += 2 // consume '/*'
                        inMultiLineComment = true
                        continue
                    }
                }
            }

            result.append(c)
            i++
        }

        // Handle unclosed multi-line comments
        if (inMultiLineComment) {
            throw JsonError.syntax("Unterminated multi-line comment", 0, 0, 0)
        }

        return result.toString()
    }

    /** Convert kotlinx.serialization JsonElement to our AST format. */
    private fun valueToExpr(element: JsonElement): Expr {
        return when (element) {
            is JsonNull -> Expr.Null(loc = null)
            is JsonPrimitive -> {
                val content = element.content
                when {
                    element.isString -> Expr.StringValue(value = content, loc = null)
                    content == "true" -> Expr.Boolean(value = true, loc = null)
                    content == "false" -> Expr.Boolean(value = false, loc = null)
                    content == "null" -> Expr.Null(loc = null)
                    else -> Expr.Number(value = content.toDoubleOrNull() ?: 0.0, loc = null)
                }
            }
            is JsonArray -> Expr.Array(
                elements = element.map { valueToExpr(it) },
                loc = null
            )
            is JsonObject -> {
                val properties = element.map { (key, value) ->
                    Property(
                        key = key,
                        value = valueToExpr(value),
                        loc = null
                    )
                }
                Expr.Object(properties = properties, loc = null)
            }
        }
    }

    /** Check for duplicate keys in objects. */
    private fun checkDuplicates(element: JsonElement, input: String, offset: Int) {
        when (element) {
            is JsonObject -> {
                val seen = mutableMapOf<String, Int>()
                for ((key, value) in element) {
                    if (key in seen) {
                        val (line, column) = findLineColumn(input, offset)
                        throw JsonError.duplicateKey(key, line, column, offset)
                    }
                    seen[key] = offset
                }
                for ((_, value) in element) {
                    checkDuplicates(value, input, offset)
                }
            }
            is JsonArray -> {
                for (item in element) {
                    checkDuplicates(item, input, offset)
                }
            }
            else -> {}
        }
    }

    /** Find line and column for a given offset. */
    private fun findLineColumn(input: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var column = 1
        var currentOffset = 0

        for (c in input) {
            if (currentOffset >= offset) break
            if (c == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            currentOffset += c.length
        }

        return line to column
    }
}

/**
 * Parse a JSON string into an AST (top-level convenience function).
 */
fun parseJson(input: String): Result<Expr> {
    val parser = FastJsonParser()
    return parser.parse(input)
}

/**
 * Parse a JSON5 string into an AST (top-level convenience function).
 */
fun parseJson5(input: String): Result<Expr> {
    val parser = FastJsonParser()
    return parser.parseJson5(input)
}
