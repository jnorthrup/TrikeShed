package borg.trikeshed.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify

/** Endpoint roster name × model ID. These are routing identities, never credential values. */
typealias EndpointModel = Join<String, String>

/** Authored chat limits and ordered admissible routes, shared by HTTP and LCNC. */
data class BrainChatOptions(
    val maxTokens: Int = 512,
    val temperature: Double = 0.2,
    val models: Series<EndpointModel> = emptySeriesOf(),
) {
    init {
        require(maxTokens > 0) { "maxTokens must be a positive integer" }
        require(temperature.isFinite() && temperature in 0.0..2.0) { "temperature must be between 0 and 2" }
        require(models.view.all { it.a.isNotBlank() && it.b.isNotBlank() }) { "models require endpoint key and model" }
    }

    companion object {
        fun parse(fields: Map<*, *>): BrainChatOptions {
            fun number(name: String, fallback: Double): Double {
                val value = fields[name] ?: return fallback
                return when (value) {
                    is Number -> value.toDouble()
                    is String -> value.toDoubleOrNull()
                    else -> null
                } ?: throw IllegalArgumentException("$name must be numeric")
            }
            val tokens = number("maxTokens", 512.0)
            require(tokens.isFinite() && tokens >= 1 && tokens <= Int.MAX_VALUE && tokens % 1.0 == 0.0) {
                "maxTokens must be a positive integer"
            }
            val raw = fields["models"]
            val parsed = when {
                raw == null || raw is String && raw.isBlank() -> emptyList<Any?>()
                raw is String -> try { reify(raw) } catch (_: Exception) {
                    throw IllegalArgumentException("models must be a list of endpoint/model pairs")
                }
                else -> raw
            }
            val rows = parsed as? List<*> ?: throw IllegalArgumentException("models must be a list of endpoint/model pairs")
            // Validate and freeze every row before any provider is admitted.
            val routes = rows.map { row ->
                val pair = row as? Map<*, *> ?: throw IllegalArgumentException("models require endpoint key and model")
                val key = (pair["key"] as? String)?.trim().orEmpty()
                val model = (pair["model"] as? String)?.trim().orEmpty()
                require(key.isNotEmpty() && model.isNotEmpty()) { "models require endpoint key and model" }
                key j model
            }.toSeries()
            return BrainChatOptions(tokens.toInt(), number("temperature", 0.2), routes)
        }
    }
}
