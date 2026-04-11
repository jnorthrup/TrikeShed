package borg.literbike.bin

/**
 * Ollama Emulator - With Quota Tracking.
 * Routes requests to multiple AI providers based on environment API keys.
 * Ported from literbike/src/bin/ollama_emulator.rs.
 */

/**
 * Model representation matching OpenAI API format.
 */
data class Model(
    val id: String,
    val ownedBy: String,
    val upstreamId: String? = null,
    val pipelineTag: String? = null,
    val downloads: Long? = null,
    val likes: Long? = null,
    val cardData: Map<String, Any>? = null
)

/**
 * Provider usage tracking.
 */
data class ProviderUsage(
    var totalTokens: Long = 0L,
    var totalCost: Double = 0.0,
    var requestCount: Long = 0L
)

/**
 * Provider model with metadata.
 */
data class ProviderModel(
    val id: String,
    val ownedBy: String,
    val upstreamId: String? = null,
    val modifiedAt: String? = "2023-11-01T00:00:00Z",
    val pipelineTag: String? = "text-generation",
    val downloads: Long? = null,
    val likes: Long? = null,
    val tags: List<String> = emptyList(),
    val cardData: Map<String, Any>? = null
) {
    companion object {
        fun synthetic(providerId: String, suffix: String): ProviderModel = ProviderModel(
            id = "$providerId/$suffix",
            ownedBy = providerId
        )
    }

    fun toOllamaTag(): Map<String, Any> {
        val details = mutableMapOf<String, Any>(
            "family" to (pipelineTag ?: "unknown")
        )
        downloads?.let { details["downloads"] = it }
        likes?.let { details["likes"] = it }
        if (tags.isNotEmpty()) details["tags"] = tags
        cardData?.let { details["cardData"] = it }

        return mapOf(
            "name" to "$id:latest",
            "model" to "$id:latest",
            "modifiedAt" to (modifiedAt ?: "2023-11-01T00:00:00Z"),
            "size" to 0,
            "digest" to "metadata",
            "details" to details
        )
    }

    fun toOpenAIModel(): Model = Model(
        id = id,
        ownedBy = ownedBy,
        upstreamId = upstreamId,
        pipelineTag = pipelineTag,
        downloads = downloads,
        likes = likes,
        cardData = cardData
    )
}

/**
 * Provider configuration.
 */
data class Provider(
    val id: String,
    val apiKey: String,
    val baseUrl: String,
    var usage: ProviderUsage = ProviderUsage(),
    val models: List<ProviderModel> = emptyList()
)

/**
 * Application state.
 */
class OllamaEmulatorState(
    val providers: List<Provider>
) {
    companion object {
        private val PROVIDER_URLS = mapOf(
            "OPENAI" to "https://api.openai.com/v1",
            "GROQ" to "https://api.groq.com/openai/v1",
            "DEEPSEEK" to "https://api.deepseek.com",
            "MOONSHOT" to "https://api.moonshot.cn/v1",
            "XAI" to "https://api.x.ai/v1",
            "PERPLEXITY" to "https://api.perplexity.ai",
            "OPENROUTER" to "https://openrouter.ai/api/v1",
            "NVIDIA" to "https://integrate.api.nvidia.com/v1",
            "CEREBRAS" to "https://api.cerebras.ai/v1",
            "HUGGINGFACE" to "https://api-inference.huggingface.co/v1",
            "KILO" to "https://api.kilo.ai/v1",
            "KILOCODE" to "https://api.kilocode.ai/v1"
        )

        fun defaultModels(providerId: String): List<ProviderModel> = listOf(
            ProviderModel.synthetic(providerId, "${providerId}-model"),
            ProviderModel.synthetic(providerId, "default")
        )

        suspend fun create(): OllamaEmulatorState {
            val providers = PROVIDER_URLS.mapNotNull { (env, url) ->
                val apiKey = System.getenv("${env}_API_KEY") ?: return@mapNotNull null
                val id = env.lowercase()
                val models = defaultModels(id)
                Provider(id, apiKey, url, models = models)
            }
            return OllamaEmulatorState(providers)
        }
    }

    fun totalUsage(): Pair<Long, Double> {
        val tokens = providers.sumOf { it.usage.totalTokens }
        val cost = providers.sumOf { it.usage.totalCost }
        return tokens to cost
    }
}

/**
 * Handle HTTP requests for the Ollama emulator.
 */
fun handleRequest(method: String, path: String, state: OllamaEmulatorState): String {
    return when {
        method == "GET" && path == "/" -> "\"Ollama is running\""
        method == "GET" && path == "/api/version" -> """{"version": "0.1.24"}"""
        method == "GET" && path == "/api/tags" -> {
            val ollamaModels = state.providers.flatMap { provider ->
                provider.models.map { it.toOllamaTag() }
            }
            """{"models": ${ollamaModels}}"""
        }
        method == "GET" && (path == "/v1/models" || path == "/models") -> {
            val models = state.providers.flatMap { provider ->
                provider.models.map { it.toOpenAIModel() }
            }
            """{"object": "list", "data": ${models}}"""
        }
        method == "GET" && (path == "/quota" || path == "/usage") -> {
            val (tokens, cost) = state.totalUsage()
            val providerInfos = state.providers.map { p ->
                mapOf(
                    "id" to p.id,
                    "baseUrl" to p.baseUrl,
                    "configured" to p.apiKey.isNotEmpty(),
                    "modelCount" to p.models.size,
                    "tokens" to p.usage.totalTokens,
                    "costUsd" to p.usage.totalCost,
                    "requests" to p.usage.requestCount
                )
            }
            """{"object": "quota", "totalTokens": $tokens, "totalCostUsd": $cost, "providers": $providerInfos}"""
        }
        method == "GET" && path == "/health" -> {
            val (tokens, cost) = state.totalUsage()
            """{"status": "ready", "providers": ${state.providers.size}, "totalTokens": $tokens, "totalCostUsd": $cost}"""
        }
        else -> """{"error": "not found"}"""
    }
}

/**
 * Main entry point for Ollama Emulator.
 */
fun runOllamaEmulator(port: Int = 8888) {
    println("Ollama Emulator starting on port $port")
    println("Configure providers by setting *_API_KEY environment variables")
    println("Endpoints:")
    println("  GET /              - Status check")
    println("  GET /api/version   - Version info")
    println("  GET /api/tags      - Available models (Ollama format)")
    println("  GET /v1/models     - Available models (OpenAI format)")
    println("  GET /quota         - Usage and quota info")
    println("  GET /health        - Health check")
}
