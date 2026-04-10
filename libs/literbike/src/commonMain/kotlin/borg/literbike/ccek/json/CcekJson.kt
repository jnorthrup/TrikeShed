package borg.literbike.ccek.json

/**
 * CCEK JSON - TrikeShed Port
 *
 * Based on ~/work/TrikeShed/commonMain/kotlin/borg/trikeshed/parse/json/*
 * CCEK-only: Element/Key traits, no serde_json, pure Kotlin stdlib
 */

/**
 * JsonKey - CCEK Key for JSON parsing
 *
 * Provides factory function for creating JsonElement instances.
 */
object JsonKey {
    val FACTORY: () -> JsonElement = { JsonElement() }
}

/**
 * JsonElement - CCEK Element tracking JSON parse stats
 */
class JsonElement {
    private var bytesParsed: Long = 0L
    private var valuesReified: Long = 0L

    companion object {
        fun create(): JsonElement = JsonElement()
    }

    fun recordParse(bytes: Int) {
        bytesParsed += bytes
    }

    fun recordReify() {
        valuesReified++
    }

    fun getBytesParsed(): Long = bytesParsed
    fun getValuesReified(): Long = valuesReified
}

/**
 * Parse JSON string (CCEK entry point)
 */
fun parse(json: String): JsonValue {
    return JsonParser.parse(json)
}

/**
 * Create structural bitmap from JSON bytes
 */
fun encodeBitmap(input: ByteArray): ByteArray {
    return JsonBitmap.encode(input)
}
