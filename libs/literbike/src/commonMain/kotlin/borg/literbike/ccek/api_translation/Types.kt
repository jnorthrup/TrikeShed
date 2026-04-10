package borg.literbike.ccek.api_translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * N-Way API Translation Layer
 *
 * Unified API translation between all major AI providers:
 * - Gemini (Google)
 * - Codex/OpenAI (OpenAI)
 * - Anthropic (Claude)
 * - DeepSeek R1
 * - WebSearch (Brave, Tavily, Serper)
 * - Moonshot/Kimi, Groq, xAI/Grok, Cohere, Mistral, Perplexity, OpenRouter, NVIDIA, Cerebras
 */

/**
 * Message role
 */
enum class MessageRole {
    System, User, Assistant, Tool
}

/**
 * Image URL for multimodal content
 */
data class ImageUrl(
    val url: String,
    val detail: String? = null
)

/**
 * Content part - text or image
 */
sealed class ContentPart {
    data class TextPart(val text: String) : ContentPart()
    data class ImagePart(val imageUrl: ImageUrl) : ContentPart()
}

/**
 * Tool call from assistant
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * Message content - either plain text or structured parts
 */
sealed class MessageContent {
    data class TextContent(val text: String) : MessageContent()
    data class PartsContent(val parts: List<ContentPart>) : MessageContent()

    companion object {
        fun fromString(s: String): MessageContent = TextContent(s)
    }

    fun toText(): String = when (this) {
        is TextContent -> text
        is PartsContent -> parts.filterIsInstance<ContentPart.TextPart>().joinToString(" ") { it.text }
    }
}

/**
 * Unified message
 */
data class UnifiedMessage(
    val role: MessageRole,
    val content: MessageContent,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
)

/**
 * Tool definition
 */
data class ToolDefinition(
    val name: String,
    val description: String? = null,
    val parameters: String // JSON string
)

/**
 * Unified chat request
 */
data class UnifiedChatRequest(
    val model: String,
    val messages: List<UnifiedMessage>,
    val temperature: Float? = null,
    val maxTokens: UInt? = null,
    val stream: Boolean? = null,
    val tools: List<ToolDefinition>? = null,
    val system: String? = null
)

/**
 * Usage statistics
 */
data class Usage(
    val promptTokens: UInt,
    val completionTokens: UInt,
    val totalTokens: UInt
)

/**
 * Choice from response
 */
data class Choice(
    val index: UInt,
    val message: UnifiedMessage,
    val finishReason: String? = null
)

/**
 * Unified chat response
 */
data class UnifiedChatResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

/**
 * Unified search request
 */
data class UnifiedSearchRequest(
    val query: String,
    val numResults: UInt? = null,
    val searchDepth: String? = null
)

/**
 * Search result
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val score: Float? = null
)

/**
 * Unified search response
 */
data class UnifiedSearchResponse(
    val query: String,
    val results: List<SearchResult>
)

/**
 * AI Provider enumeration
 */
enum class Provider(
    val baseUrl: String,
    val isOpenAIPatible: Boolean = false
) {
    OpenAI("https://api.openai.com/v1", isOpenAIPatible = true),
    Anthropic("https://api.anthropic.com"),
    Gemini("https://generativelanguage.googleapis.com/v1beta"),
    DeepSeek("https://api.deepseek.com", isOpenAIPatible = true),
    Moonshot("https://api.moonshot.cn/v1", isOpenAIPatible = true),
    Groq("https://api.groq.com/openai/v1", isOpenAIPatible = true),
    XAI("https://api.x.ai/v1", isOpenAIPatible = true),
    Cohere("https://api.cohere.ai"),
    Mistral("https://api.mistral.ai/v1", isOpenAIPatible = true),
    Perplexity("https://api.perplexity.ai", isOpenAIPatible = true),
    OpenRouter("https://openrouter.ai/api/v1", isOpenAIPatible = true),
    NVIDIA("https://integrate.api.nvidia.com/v1", isOpenAIPatible = true),
    Cerebras("https://api.cerebras.ai/v1", isOpenAIPatible = true),
    BraveSearch("https://api.search.brave.com/res/v1/web/search"),
    TavilySearch("https://api.tavily.com/search"),
    SerperSearch("https://google.serper.dev/search");

    val isSearchProvider: Boolean
        get() = this in listOf(BraveSearch, TavilySearch, SerperSearch)

    companion object {
        fun fromEnvKey(key: String): Provider? = when {
            key.uppercase().contains("OPENAI") -> OpenAI
            key.uppercase().contains("ANTHROPIC") -> Anthropic
            key.uppercase().contains("GEMINI") || key.uppercase().contains("GOOGLE") -> Gemini
            key.uppercase().contains("DEEPSEEK") -> DeepSeek
            key.uppercase().contains("MOONSHOT") || key.uppercase().contains("KIMI") -> Moonshot
            key.uppercase().contains("GROQ") -> Groq
            key.uppercase().contains("XAI") || key.uppercase().contains("GROK") -> XAI
            key.uppercase().contains("COHERE") -> Cohere
            key.uppercase().contains("MISTRAL") -> Mistral
            key.uppercase().contains("PERPLEXITY") -> Perplexity
            key.uppercase().contains("OPENROUTER") -> OpenRouter
            key.uppercase().contains("NVIDIA") -> NVIDIA
            key.uppercase().contains("CEREBRAS") -> Cerebras
            key.uppercase().contains("BRAVE") -> BraveSearch
            key.uppercase().contains("TAVILY") -> TavilySearch
            key.uppercase().contains("SERPER") -> SerperSearch
            else -> null
        }
    }
}
