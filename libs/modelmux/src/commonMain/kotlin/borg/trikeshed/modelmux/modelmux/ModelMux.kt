package borg.trikeshed.modelmux.modelmux

import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.get
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.UUID

/**
 * ModelProxyConfig - Configuration for the ModelMux proxy server
 * Plain data class (no @Serializable) - config loaded via ModelMuxConfig
 */
data class ModelProxyConfig(
    val bindAddress: String = "0.0.0.0",
    val port: Int = 8888,
    val enableStreaming: Boolean = true,
    val enableCaching: Boolean = true,
    val defaultModel: String? = null,
    val fallbackModel: String? = null,
    val requestTimeoutSecs: Int = 120,
    val maxRetries: Int = 2,
)

/**
 * Chat Completion Request parsed from ConfixDoc (OpenAI compatible)
 * Uses Facet projections for field access instead of data class properties
 */
class ChatCompletionRequest(private val doc: ConfixDoc) {
    
    /** Model ID (e.g., "gpt-4", "kilo_code/deepseek-chat") */
    val model: String get() = doc.scalar("model") as? String ?: ""
    
    /** Messages array - each message is a ConfixCell */
    val messages: List<ChatMessage> get() {
        val cells = doc.docAt("messages")?.cellKids ?: 0 j { _ -> error("No messages") }
        return cells.size j { i -> ChatMessage(cells[i]) }
    }
    
    /** Temperature (optional) */
    val temperature: Double? get() = doc.scalar("temperature") as? Double
    
    /** Max tokens (optional) */
    val maxTokens: Int? get() = doc.scalar("max_tokens") as? Int
    
    /** Stream flag (optional) */
    val stream: Boolean? get() = doc.scalar("stream") as? Boolean
    
    /** Tools (optional) */
    val tools: List<Any>? get() = doc.scalar("tools") as? List<Any>
    
    /** Tool choice (optional) */
    val toolChoice: Any? get() = doc.scalar("tool_choice")
    
    companion object Parse {
        /** Parse JSON string into ChatCompletionRequest */
        fun parse(jsonText: String): ChatCompletionRequest = ChatCompletionRequest(confixDoc(jsonText))
        
        /** Parse byte array into ChatCompletionRequest */
        fun parse(bytes: ByteArray): ChatCompletionRequest = ChatCompletionRequest(confixDoc(bytes))
    }
}

/** Single chat message with facet-based access */
class ChatMessage(private val cell: borg.trikeshed.parse.confix.ConfixCell) {
    val role: String = cell.scalar("role") as? String ?: ""
    val content: String? = cell.scalar("content") as? String
    val name: String? = cell.scalar("name") as? String
    val toolCalls: List<Any>? = cell.scalar("tool_calls") as? List<Any>
    val toolCallId: String? = cell.scalar("tool_call_id") as? String
}

/**
 * Chat Completion Response - built using kernel algebra (Join/Series)
 * No @Serializable - we construct JSON via ConfixDoc or string building
 */
class ChatCompletionResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?,
    val created: Long = System.currentTimeMillis() / 1000,
    val object: String = "chat.completion",
) {
    /** Emit as JSON string for HTTP response */
    fun toJson(): String {
        val choicesJson = choices.map { it.toJson() }.joinToString(",")
        val usageJson = usage?.toJson() ?: "null"
        return """
            {
                "id": "$id",
                "object": "$object",
                "created": $created,
                "model": "$model",
                "choices": [$choicesJson],
                "usage": $usageJson
            }
        """.trimIndent()
    }
}

/** Choice in completion response */
class Choice(
    val index: Int,
    val message: ChatMessage,
    val finishReason: String?,
) {
    fun toJson(): String {
        return """
            {
                "index": $index,
                "message": {
                    "role": "${message.role}",
                    "content": ${if (message.content != null) "\"${message.content!.replace("\"", "\\\"")}\"" else "null"}
                },
                "finish_reason": ${if (finishReason != null) "\"$finishReason\"" else "null"}
            }
        """.trimIndent()
    }
}

/** Token usage */
class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
) {
    fun toJson(): String = """{"prompt_tokens": $promptTokens, "completion_tokens": $completionTokens, "total_tokens": $totalTokens}"""
}

/**
 * Models List Response - built using kernel algebra
 */
class ModelsResponse(
    val data: List<ModelInfo>,
    val object: String = "list",
) {
    fun toJson(): String {
        val modelsJson = data.map { it.toJson() }.joinToString(",")
        return """{"object": "$object", "data": [$modelsJson]}"""
    }
}

/** Model info entry */
class ModelInfo(
    val id: String,
    val ownedBy: String,
    val object: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
) {
    fun toJson(): String = """{"id": "$id", "object": "$object", "created": $created, "owned_by": "$ownedBy"}"""
}

/**
 * RouteResult - Result of DSEL routing
 */
data class RouteResult(
    val provider: String,
    val baseUrl: String,
    val apiKey: String,
)

/**
 * ModelProxy - CCEK KeyedService for OpenAI-compatible model proxy
 * Uses Confix/Facet for serialization, LCNC Grid for data transformation
 */
class ModelProxy(
    private val config: ModelProxyConfig,
    private val keyStore: borg.trikeshed.modelmux.keymux.KeyStore,
    private val dselRouter: borg.trikeshed.modelmux.keymux.DselRouter,
    parentJob: CompletableJob? = null
) : AsyncContextElement(ElementState.CREATED, parentJob), KeyedService {

    companion object Key : kotlinx.coroutines.CoroutineContext.Key<ModelProxy>()

    override val key: kotlinx.coroutines.CoroutineContext.Key<*> get() = Key

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(config.requestTimeoutSecs.toLong()))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build()

    /** Initialize the proxy */
    suspend fun initialize(): ModelProxy {
        requireState(ElementState.CREATED)
        state = ElementState.OPEN

        // Warm up model cache
        refreshModelsCache()

        state = ElementState.ACTIVE
        return this
    }

    /** Handle chat completion request - parses ConfixDoc from body */
    suspend fun chatCompletion(body: String): ChatCompletionResponse {
        checkState()
        
        val request = ChatCompletionRequest.Parse.parse(body)
        
        val route = dselRouter.route(request.model)
            ?: throw IllegalArgumentException("No provider available for model: ${request.model}. Available: ${dselRouter.getProviderStatus().joinToString { it.name }}")

        // Track token usage (estimate)
        val estimatedTokens = estimateTokens(request.messages)
        keyStore.recordUsage(route.provider, estimatedTokens.toLong())

        // Forward request to provider
        return forwardToProvider(route, request)
    }

    /** Handle streaming chat completion */
    suspend fun chatCompletionStream(body: String): Channel<String> {
        checkState()
        
        val request = ChatCompletionRequest.Parse.parse(body)

        val route = dselRouter.route(request.model)
            ?: throw IllegalArgumentException("No provider available for model: ${request.model}")

        val estimatedTokens = estimateTokens(request.messages)
        keyStore.recordUsage(route.provider, estimatedTokens.toLong())

        return forwardToProviderStream(route, request)
    }

    /** Get available models - returns ModelsResponse built via kernel algebra */
    suspend fun getModels(): ModelsResponse {
        checkState()

        val providerStatuses = dselRouter.getProviderStatus()
        val models = mutableListOf<ModelInfo>()

        for (status in providerStatuses) {
            if (status.hasKey) {
                val providerModels = fetchModelsFromProvider(status).await()
                models.addAll(providerModels)
            }
        }

        // Add default/fallback models if configured
        config.defaultModel?.let { model ->
            if (!models.any { it.id == model }) {
                models.add(ModelInfo(id = model, ownedBy = "configured"))
            }
        }

        return ModelsResponse(data = models)
    }

    /** Health check endpoint - returns Map built via kernel algebra */
    suspend fun health(): Map<String, Any> {
        val providerStatuses = dselRouter.getProviderStatus()
        val healthyProviders = providerStatuses.count { it.hasKey }

        return mapOf(
            "status" to if (healthyProviders > 0) "healthy" else "degraded",
            "providers_available" to healthyProviders,
            "total_providers" to providerStatuses.size,
            "providers" to providerStatuses.map { it.name },
            "default_model" to config.defaultModel,
            "fallback_model" to config.fallbackModel,
        )
    }

    /** Refresh model cache from all providers */
    private suspend fun refreshModelsCache() {
        // Could implement background cache refresh here
    }

    /** Estimate token count from messages */
    private fun estimateTokens(messages: List<ChatMessage>): Int {
        return messages.sumOf { (it.content?.length ?: 0) / 4 + 10 }
    }

    /** Forward request to provider - uses ConfixDoc for response parsing */
    private suspend fun forwardToProvider(route: RouteResult, request: ChatCompletionRequest): ChatCompletionResponse {
        val url = "${route.baseUrl}/chat/completions"

        // Build request JSON using kernel algebra (no kotlinx.serialization)
        val requestJson = buildChatRequestJson(request)
        
        val httpRequest = HttpRequest.newBuilder()
            .uri(java.net.URI(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${route.apiKey}")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))
            .timeout(Duration.ofSeconds(config.requestTimeoutSecs.toLong()))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RuntimeException("Provider ${route.provider} returned ${response.statusCode()}: ${response.body()}")
        }

        // Parse response using ConfixDoc (Facet projections)
        return parseChatResponse(response.body(), route.provider)
    }

    /** Build chat completion request JSON using kernel algebra */
    private fun buildChatRequestJson(request: ChatCompletionRequest): String {
        val messagesJson = request.messages.map { msg ->
            val content = if (msg.content != null) "\"${msg.content!.replace("\"", "\\\"")}\"" else "null"
            """{"role": "${msg.role}", "content": $content}"""
        }.joinToString(",")

        val temperature = request.temperature?.let { "\"temperature\": $it" } ?: ""
        val maxTokens = request.maxTokens?.let { "\"max_tokens\": $it" } ?: ""
        val stream = request.stream?.let { "\"stream\": $it" } ?: ""

        val extras = listOf(temperature, maxTokens, stream).filter { it.isNotBlank() }.joinToString(",")
        val extraPrefix = if (extras.isNotBlank()) ", $extras" else ""

        return """{"model": "${request.model}", "messages": [$messagesJson]$extraPrefix}"""
    }

    /** Parse chat completion response using ConfixDoc */
    private fun parseChatResponse(body: String, provider: String): ChatCompletionResponse {
        val doc = confixDoc(body)
        
        val id = doc.scalar("id") as? String ?: UUID.randomUUID().toString()
        val model = doc.scalar("model") as? String ?: "unknown"
        val created = (doc.scalar("created") as? Long) ?: (System.currentTimeMillis() / 1000)
        
        val choicesCells = doc.docAt("choices")?.cellKids ?: 0 j { _ -> emptyList() }
        val choices = choicesCells.size j { i ->
            val choiceCell = choicesCells[i]
            val index = choiceCell.scalar("index") as? Int ?: i
            val messageCell = choiceCell.get("message") ?: error("No message in choice")
            val message = ChatMessage(messageCell)
            val finishReason = choiceCell.scalar("finish_reason") as? String
            Choice(index, message, finishReason)
        }
        
        val usage = doc.docAt("usage")?.let { usageCell ->
            Usage(
                promptTokens = usageCell.scalar("prompt_tokens") as? Int ?: 0,
                completionTokens = usageCell.scalar("completion_tokens") as? Int ?: 0,
                totalTokens = usageCell.scalar("total_tokens") as? Int ?: 0,
            )
        }

        return ChatCompletionResponse(id, model, choices, usage, created)
    }

    /** Forward streaming request to provider */
    private suspend fun forwardToProviderStream(route: RouteResult, request: ChatCompletionRequest): Channel<String> {
        val channel = Channel<String>(Channel.UNLIMITED)

        launch(this.supervisor) {
            try {
                val url = "${route.baseUrl}/chat/completions"
                val requestJson = buildChatRequestJson(request)

                val httpRequest = HttpRequest.newBuilder()
                    .uri(java.net.URI(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer ${route.apiKey}")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .timeout(Duration.ofSeconds(config.requestTimeoutSecs.toLong()))
                    .build()

                val httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines())

                httpResponse.body().forEach { line ->
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6)
                        if (data != "[DONE]") {
                            channel.send(data)
                        }
                    }
                }
            } catch (e: Exception) {
                channel.send("""{"error": "${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""")
            } finally {
                channel.close()
            }
        }

        return channel
    }

    /** Fetch models from a specific provider - uses ConfixDoc parsing */
    private fun fetchModelsFromProvider(status: borg.trikeshed.modelmux.keymux.ProviderStatus): Deferred<List<ModelInfo>> = async {
        val key = keyStore.getKey(status.name) ?: return@async emptyList()
        val url = "${status.baseUrl}/models"

        val httpRequest = HttpRequest.newBuilder()
            .uri(java.net.URI(url))
            .header("Authorization", "Bearer ${key.key}")
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        return try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                parseModelsResponse(response.body(), status.name)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Parse models response using ConfixDoc */
    private fun parseModelsResponse(body: String, provider: String): List<ModelInfo> {
        return try {
            val doc = confixDoc(body)
            
            // Try "data" field first (OpenAI format), then "models" (Gemini format)
            val cells = doc.docAt("data")?.cellKids 
                ?: doc.docAt("models")?.cellKids 
                ?: 0 j { _ -> emptyList() }
            
            cells.size j { i ->
                val cell = cells[i]
                val rawId = (cell.scalar("id") as? String) 
                    ?: (cell.scalar("name") as? String) 
                    ?: return@j ModelInfo("$provider/unknown", provider)
                val cleanId = rawId.removePrefix("models/")
                ModelInfo("$provider/$cleanId", provider)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun checkState() {
        if (state != ElementState.ACTIVE) {
            throw IllegalStateException("ModelProxy not active. Current state: $state")
        }
    }

    companion object Factory {
        fun create(
            config: ModelProxyConfig,
            keyStore: borg.trikeshed.modelmux.keymux.KeyStore,
            dselRouter: borg.trikeshed.modelmux.keymux.DselRouter,
            parentJob: CompletableJob? = null
        ): ModelProxy {
            return ModelProxy(config, keyStore, dselRouter, parentJob)
        }
    }
}