package borg.literbike.ccek.api_translation

/**
 * OpenAI API Format
 * Ported from literbike/src/ccek/api_translation/src/openai.rs
 */

object OpenAIClient {
    fun baseUrl(): String = Provider.OpenAI.baseUrl()
}
