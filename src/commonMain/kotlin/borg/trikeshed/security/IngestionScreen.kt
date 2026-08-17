package borg.trikeshed.security

object IngestionScreen {

    private val injectionPatterns = listOf(
        Regex("ignore previous instructions", RegexOption.IGNORE_CASE),
        Regex("system prompt:", RegexOption.IGNORE_CASE),
        Regex("you are an ai", RegexOption.IGNORE_CASE),
        Regex("disregard previous", RegexOption.IGNORE_CASE),
        Regex("forget all previous", RegexOption.IGNORE_CASE)
    )

    /**
     * Scans code comments, docstrings, and schema definitions for embedded instruction sets.
     * Returns true if a potential prompt injection is detected.
     */
    fun hasEmbeddedInstructions(input: String): Boolean {
        return injectionPatterns.any { it.containsMatchIn(input) }
    }

    /**
     * Throws an exception if embedded instruction sets are detected in the input.
     */
    fun validateSafe(input: String) {
        if (hasEmbeddedInstructions(input)) {
            throw IllegalArgumentException("Potential prompt injection detected in input.")
        }
    }

    /**
     * Sanitizes HTML content. Moved from GalleryRenderer to be available here.
     */
    fun sanitizeHtml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#039;")
    }
}
