package borg.literbike.ccek.keymux.protocols

/**
 * Model mapping configuration for provider-specific translations
 */
data class ModelMapping(
    val haikuModel: String? = null,
    val sonnetModel: String? = null,
    val opusModel: String? = null,
    val defaultModel: String? = null
) {
    fun mapModel(originalModel: String): String {
        val lower = originalModel.lowercase()

        if (lower.contains("haiku")) {
            haikuModel?.let { return it }
        }
        if (lower.contains("sonnet")) {
            sonnetModel?.let { return it }
        }
        if (lower.contains("opus")) {
            opusModel?.let { return it }
        }

        return defaultModel ?: originalModel
    }
}
