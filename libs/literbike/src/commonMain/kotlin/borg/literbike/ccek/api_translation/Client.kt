package borg.literbike.ccek.api_translation

import kotlinx.serialization.json.*

/**
 * OpenAI API Format
 */
object OpenAIClient {
    fun baseUrl(): String = Provider.OpenAI.baseUrl
}

/**
 * Anthropic API Format
 */
object AnthropicClient {
    fun baseUrl(): String = Provider.Anthropic.baseUrl
}

/**
 * Gemini API Format
 */
object GeminiClient {
    fun baseUrl(): String = Provider.Gemini.baseUrl
}

/**
 * DeepSeek API Format
 */
object DeepSeekClient {
    fun baseUrl(): String = Provider.DeepSeek.baseUrl
}

/**
 * Brave Search API Format
 */
object BraveSearchClient {
    fun baseUrl(): String = Provider.BraveSearch.baseUrl
}

/**
 * Tavily Search API Format
 */
object TavilySearchClient {
    fun baseUrl(): String = Provider.TavilySearch.baseUrl
}

/**
 * Serper Search API Format
 */
object SerperSearchClient {
    fun baseUrl(): String = Provider.SerperSearch.baseUrl
}

/**
 * UnifiedClient - N-Way provider access
 */
class UnifiedClient(
    private val provider: Provider,
    private val apiKey: String
) {
    companion object {
        fun create(provider: Provider, apiKey: String): UnifiedClient = UnifiedClient(provider, apiKey)
    }

    fun provider(): Provider = provider
    fun baseUrl(): String = provider.baseUrl

    /**
     * Build chat URL for the provider
     */
    fun chatUrl(request: UnifiedChatRequest): String {
        return if (provider.isOpenAIPatible) {
            "${provider.baseUrl}/chat/completions"
        } else {
            when (provider) {
                Provider.Anthropic -> "${provider.baseUrl}/v1/messages"
                Provider.Gemini -> "${provider.baseUrl}/models/${request.model}:generateContent?key=$apiKey"
                else -> "${provider.baseUrl}/chat/completions"
            }
        }
    }

    /**
     * Build request body for the provider
     */
    fun buildRequestBody(request: UnifiedChatRequest): JsonObject {
        return if (provider.isOpenAIPatible) {
            ApiConverter.toOpenAi(request)
        } else {
            when (provider) {
                Provider.Anthropic -> ApiConverter.toAnthropic(request)
                Provider.Gemini -> ApiConverter.toGemini(request)
                else -> ApiConverter.toOpenAi(request)
            }
        }
    }

    /**
     * Build auth headers for the provider
     */
    fun authHeaders(): Map<String, String> {
        return when (provider) {
            Provider.Anthropic -> mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
            Provider.Gemini -> emptyMap() // Key is in URL
            Provider.BraveSearch -> mapOf("X-Subscription-Token" to apiKey)
            Provider.SerperSearch -> mapOf("X-API-KEY" to apiKey)
            Provider.TavilySearch -> emptyMap() // Key is in body
            else -> mapOf("Authorization" to "Bearer $apiKey")
        }
    }

    /**
     * Parse response from provider
     */
    fun parseResponse(response: JsonObject): UnifiedChatResponse? {
        return if (provider.isOpenAIPatible) {
            ApiConverter.fromOpenAi(response)
        } else {
            when (provider) {
                Provider.Anthropic -> ApiConverter.fromAnthropic(response)
                Provider.Gemini -> ApiConverter.fromGemini(response)
                else -> ApiConverter.fromOpenAi(response)
            }
        }
    }

    /**
     * Parse search response
     */
    fun parseSearchResponse(response: JsonObject): UnifiedSearchResponse {
        val results = when (provider) {
            Provider.BraveSearch -> {
                val web = response["web"]?.jsonObject
                web?.get("results")?.jsonArray ?: JsonArray(emptyList())
            }
            Provider.TavilySearch -> response["results"]?.jsonArray ?: JsonArray(emptyList())
            Provider.SerperSearch -> response["organic"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        val parsed = results.mapNotNull { r ->
            val obj = r.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val score = obj["score"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            SearchResult(title = title, url = url, snippet = snippet, score = score)
        }

        return UnifiedSearchResponse(query = "", results = parsed)
    }

    /**
     * Build search request body
     */
    fun buildSearchBody(query: String): JsonObject {
        return when (provider) {
            Provider.BraveSearch -> buildJsonObject {
                put("q", JsonPrimitive(query))
                put("count", JsonPrimitive(10))
            }
            Provider.TavilySearch -> buildJsonObject {
                put("query", JsonPrimitive(query))
                put("api_key", JsonPrimitive(apiKey))
            }
            Provider.SerperSearch -> buildJsonObject {
                put("q", JsonPrimitive(query))
            }
            else -> buildJsonObject {
                put("query", JsonPrimitive(query))
            }
        }
    }
}
