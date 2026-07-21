/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package modelmux

import keymux.KeyMux
import modelmux.acp.AcpModelCard
import modelmux.acp.AcpMessage
import modelmux.acp.AcpResponse
import modelmux.acp.AcpChunk
import modelmux.acp.AcpCodec
import modelmux.acp.AcpAction
import modelmux.acp.CapabilityRouter
import borg.trikeshed.lib.*
import borg.trikeshed.parse.json.JsonSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

/**
 * ACP-compatible ModelMux with real HTTP backing.
 * 
 * Each model card points at an ACP endpoint (NIM, Ollama, OpenAI-compatible, etc.).
 * The mux routes calls to the correct endpoint based on capability requirements.
 */
class ModelMux(
    private val keyMux: KeyMux,
    private val configure: ModelMuxBuilder.() -> Unit = {},
) {
    private val builder = ModelMuxBuilder(keyMux).apply(configure)
    private val httpClient = HttpClient.newBuilder().build()
    private val modelCards = mutableListOf<AcpModelCard>()
    private val endpoints = mutableMapOf<String, String>()

    init {
        builder.cards.forEach { card ->
            modelCards += card
            if (builder.endpoints.containsKey(card.id)) {
                endpoints[card.id] = builder.endpoints[card.id]!!
            }
        }
    }

    /** Register a model card with its ACP endpoint */
    fun registerModel(card: AcpModelCard, baseUrl: String) {
        modelCards += card
        endpoints[card.id] = baseUrl
    }

    /** List all registered models with their capabilities */
    fun listModels(): Series<AcpModelCard> = modelCards.toSeries()

    /** Find a model that supports the required action and capabilities */
    fun selectModel(action: AcpAction, requiredCaps: Series<String>): AcpModelCard? {
        return CapabilityRouter().route(modelCards.toSeries(), action, requiredCaps).a.lastOrNull()
    }

    /** Chat completion (non-streaming) */
    suspend fun chat(modelId: String, messages: Series<AcpMessage>): AcpResponse = withContext(Dispatchers.IO) {
        val baseUrl = endpoints[modelId] ?: error("No endpoint registered for model: $modelId")
        val key = resolveKey(modelId)
        
        val body = buildString {
            append("{\"model\":\"$modelId\",\"messages\":[")
            messages.view.forEachIndexed { i, (role, content) ->
                if (i > 0) append(",")
                append("{\"role\":\"$role\",\"content\":${jsonStr(content)}}")
            }
            append("],\"stream\":false}")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $key")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) error("ACP chat failed (${response.statusCode()}): ${response.body()}")
        AcpCodec.parseResponse(response.body())
    }

    /** Embeddings */
    suspend fun embed(modelId: String, texts: Series<String>): Series<Series<Double>> = withContext(Dispatchers.IO) {
        val baseUrl = endpoints[modelId] ?: error("No endpoint registered for model: $modelId")
        val key = resolveKey(modelId)

        val body = buildString {
            append("{\"model\":\"$modelId\",\"input\":[")
            texts.view.forEachIndexed { i, t ->
                if (i > 0) append(",")
                append(jsonStr(t))
            }
            append("]}")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/embeddings"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $key")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() >= 400) error("ACP embed failed (${response.statusCode()}): ${response.body()}")
        
        val parsed = JsonSupport.parse(response.body()) as? Map<*, *> ?: error("Bad embed response")
        val data = parsed["data"] as? List<*> ?: error("No data in embed response")
        data.map { (it as Map<*, *>)["embedding"] as List<Double> }.map { it.toSeries() }.toSeries()
    }

    /** Streaming chat - returns a channel of chunks */
    suspend fun chatStream(modelId: String, messages: Series<AcpMessage>): ReceiveChannel<AcpChunk> = withContext(Dispatchers.IO) {
        val baseUrl = endpoints[modelId] ?: error("No endpoint registered for model: $modelId")
        val key = resolveKey(modelId)
        
        val body = buildString {
            append("{\"model\":\"$modelId\",\"messages\":[")
            messages.view.forEachIndexed { i, (role, content) ->
                if (i > 0) append(",")
                append("{\"role\":\"$role\",\"content\":${jsonStr(content)}}")
            }
            append("],\"stream\":true}")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $key")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val channel = Channel<AcpChunk>(100)
        launch(Dispatchers.IO) {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines())
            try {
                response.body().forEach { line ->
                    if (line.startsWith("data:")) {
                        val chunk = AcpCodec.parseChunk(line)
                        chunk?.let { channel.trySend(it) }
                    }
                }
            } finally {
                channel.close()
            }
        }
        channel
    }

    private suspend fun resolveKey(modelId: String): String {
        return keyMux.get(modelId) ?: error("No API key for provider: $modelId (prefix: LLM_)")
    }

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    /** Close the HTTP client */
    fun close() {
        // HttpClient has no explicit close in JDK 11+, GC handles it
    }
}

/** Builder DSL for ModelMux configuration */
class ModelMuxBuilder(private val keyMux: KeyMux) {
    var cards = mutableListOf<AcpModelCard>()
    var endpoints = mutableMapOf<String, String>()

    fun card(card: AcpModelCard): ModelMuxBuilder = apply { cards += card }
    fun endpoint(modelId: String, baseUrl: String): ModelMuxBuilder = apply { endpoints[modelId] = baseUrl }

    /** Load from a config JSON file */
    fun loadConfig(path: String) {
        // TODO: parse JSON config for cards + endpoints
    }
}

fun ModelMuxBuilder(envPrefix: String = "LLM_") {
    // Keys are loaded from environment by KeyMux
}