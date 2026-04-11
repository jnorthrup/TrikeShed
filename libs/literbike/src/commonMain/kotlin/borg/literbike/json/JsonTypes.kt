package borg.literbike.json

import kotlinx.serialization.Serializable

/**
 * JSON AST node types.
 * Ported from literbike/src/json/mod.rs (Expr enum).
 *
 * Provides an AST representation compatible with Bun's Expr type.
 */

@Serializable
data class SourceLocation(
    val start: Int,
    val end: Int,
    val line: Int,
    val column: Int
) {
    companion object {
        fun new(start: Int, end: Int, line: Int, column: Int): SourceLocation =
            SourceLocation(start, end, line, column)
    }
}

@Serializable
sealed class Expr {
    data class Object(
        val properties: List<Property>,
        val loc: SourceLocation? = null
    ) : Expr()

    data class Array(
        val elements: List<Expr>,
        val loc: SourceLocation? = null
    ) : Expr()

    data class StringValue(
        val value: String,
        val loc: SourceLocation? = null
    ) : Expr()

    data class Number(
        val value: Double,
        val loc: SourceLocation? = null
    ) : Expr()

    data class Boolean(
        val value: kotlin.Boolean,
        val loc: SourceLocation? = null
    ) : Expr()

    data class Null(
        val loc: SourceLocation? = null
    ) : Expr()
}

@Serializable
data class Property(
    val key: String,
    val value: Expr,
    val loc: SourceLocation? = null
)
