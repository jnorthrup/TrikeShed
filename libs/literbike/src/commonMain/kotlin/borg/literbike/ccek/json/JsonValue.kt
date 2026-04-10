package borg.literbike.ccek.json

/**
 * JsonValue - JSON value types (CCEK-only, no serde)
 */

/** JSON value types - all owned, no lifetimes */
sealed class JsonValue {
    data object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Number(val value: Double) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Array(val values: List<JsonValue>) : JsonValue()
    data class Object(val map: Map<String, JsonValue>) : JsonValue()

    fun isNull(): Boolean = this is Null

    fun asBool(): Boolean? = when (this) {
        is Bool -> value
        else -> null
    }

    fun asNumber(): Double? = when (this) {
        is Number -> value
        else -> null
    }

    fun asStr(): String? = when (this) {
        is Str -> value
        else -> null
    }

    fun get(key: String): JsonValue? = when (this) {
        is Object -> map[key]
        else -> null
    }

    fun getIndex(idx: Int): JsonValue? = when (this) {
        is Array -> values.getOrNull(idx)
        else -> null
    }

    companion object {
        fun default(): JsonValue = Null
    }
}
