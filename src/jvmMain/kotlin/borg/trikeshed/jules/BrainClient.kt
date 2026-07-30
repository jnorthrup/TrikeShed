package borg.trikeshed.jules

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

/**
 * BrainClient — the real flywheel brain.
 *
 * OpenAI-compatible chat completions over HTTPS with multi-provider failover.
 * The flywheel's [FlywheelDriver.buildAnswer] calls this to answer Jules
 * sessions with project conventions as the system prompt.
 *
 * The brain must fire a real model. A string template is not a brain.
 *
 * Providers are discovered from the environment and tool config files. When a
 * provider returns non-200, the brain logs the failure to
 * `~/.local/forge/brain-errors.jsonl` (daemon-only) and tries the next
 * provider until one succeeds or all are exhausted.
 */
class BrainClient(
    /** If non-null, overrides auto-discovery and uses a single endpoint. */
    apiKey: String? = null,
    base: String = "https://integrate.api.nvidia.com/v1",
    model: String = "poolside/laguna-xs-2.1",
) {
    private val http: HttpClient = HttpClient.newHttpClient()

    /** One OpenAI-compatible endpoint with credentials and a default model. */
    data class Endpoint(
        val name: String,
        val apiKey: String,
        val base: String,
        val model: String,
    )

    private val endpoints: List<Endpoint> = if (apiKey != null) {
        listOf(Endpoint("override", apiKey, base, model))
    } else {
        discoverEndpoints()
    }

    /** Last endpoint that answered a chat. Quotas on NVIDIA track disjointly
     *  per model/cluster and per API-shape family, and busyness varies by
     *  promotion — so one 429 says nothing about other models. Start each
     *  call at the last known-good endpoint instead of re-paying the
     *  head-of-list 429 tax on every request. */
    @Volatile private var lastGood: Int = 0

    private val errorLog: File? = run {
        val home = System.getProperty("user.home") ?: return@run null
        val forgeDir = File(home, ".local/forge")
        forgeDir.mkdirs()
        File(forgeDir, "brain-errors.jsonl")
    }

    /** True if at least one provider endpoint was discovered. */
    fun hasEndpoints(): Boolean = endpoints.isNotEmpty()

    /** Non-streaming chat completion with multi-provider failover. */
    fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 256, temperature: Double = 0.2): String {
        if (endpoints.isEmpty()) error("Brain: no provider endpoints discovered")

        var lastError: String = "all providers exhausted"
        val start = lastGood.coerceIn(0, endpoints.size - 1)
        val order = (start until endpoints.size) + (0 until start)
        for (idx in order) {
            val ep = endpoints[idx]
            val body = buildString {
                append("""{"model":${jsonStr(ep.model)},"messages":[""")
                messages.forEachIndexed { i, (role, content) ->
                    if (i > 0) append(',')
                    append("""{"role":${jsonStr(role)},"content":${jsonStr(content)}}""")
                }
                append("],")
                append("\"max_tokens\":$maxTokens,")
                append("\"temperature\":$temperature,")
                append("\"top_p\":0.9")
                append('}')
            }
            val req = HttpRequest.newBuilder()
                .uri(URI.create("${ep.base}/chat/completions"))
                .header("Authorization", "Bearer ${ep.apiKey}")
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (t: Throwable) {
                // A hung or refused cluster must not abort failover — quotas
                // are disjoint, the next model family may answer immediately.
                lastError = "Brain ${ep.name} threw: ${t.message}"
                logError(ep.name, -1, t.message.orEmpty().take(500))
                continue
            }
            if (resp.statusCode() < 400) {
                lastGood = idx
                return extractContent(resp.body())
            }
            lastError = "Brain ${ep.name} ${resp.statusCode()}: ${resp.body().take(300)}"
            logError(ep.name, resp.statusCode(), resp.body().take(500))
        }
        error(lastError)
    }

    private fun logError(provider: String, statusCode: Int, bodySnippet: String) {
        val ts = Instant.now().toEpochMilli()
        val entry = buildString {
            append("{\"t\":")
            append(ts)
            append(",\"provider\":")
            append(jsonStr(provider))
            append(",\"status\":")
            append(statusCode)
            append(",\"body\":")
            append(jsonStr(bodySnippet))
            append('}')
        }
        errorLog?.let { f ->
            f.appendText(entry + "\n")
        }
    }

    /** Pull choices[0].message.content out of the OpenAI-compatible JSON. */
    private fun extractContent(json: String): String {
        val key = """"content""""
        val i = json.indexOf(key)
        if (i < 0) error("Brain: no content field in response: ${json.take(200)}")
        var j = json.indexOf('"', i + key.length).also { it -> if (it < 0) error("Brain: malformed content") }
        // skip the colon and whitespace until the opening quote
        j = json.indexOf('"', j).let { if (it < 0) error("Brain: missing content open quote"); it }
        val sb = StringBuilder()
        var k = j + 1
        while (k < json.length) {
            val c = json[k]
            when {
                c == '\\' && k + 1 < json.length -> {
                    when (val e = json[k + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'r' -> sb.append('\r')
                        else -> sb.append(e)
                    }
                    k += 2
                }
                c == '"' -> return sb.toString()
                else -> { sb.append(c); k++ }
            }
        }
        error("Brain: unterminated content")
    }

    /**
     * Discover all OpenAI-compatible endpoints from env vars and tool config
     * files. Scans NVIDIA, OpenRouter, OpenAI, Groq, DeepSeek, ZAI, and others.
     * Provider-specific keys are tried first; generic OpenAI-compatible keys
     * follow as fallbacks.
     */
    private fun discoverEndpoints(): List<Endpoint> {
        val out = mutableListOf<Endpoint>()
        val seen = mutableSetOf<String>()

        fun add(name: String, key: String, base: String, model: String) {
            val id = "$name:$base:$model"
            if (key.isNotBlank() && seen.add(id)) {
                out.add(Endpoint(name, key.trim(), base.trim(), model.trim()))
            }
        }

        // Primary: NVIDIA NIM — 102 free models on one key. When one model
        // 429s (per-cluster rate limit), the brain rotates to a different
        // architecture on a different cluster before bailing to paid providers.
        // No llama-family — too old. Strongest current gen first.
        System.getenv("NVIDIA_API_KEY")?.let { k ->
            val base = "https://integrate.api.nvidia.com/v1"
            add("nv-deepseek-v4-pro", k, base, "deepseek-ai/deepseek-v4-pro")
            add("nv-nemotron-super-120b", k, base, "nvidia/nemotron-3-super-120b-a12b")
            add("nv-mistral-large-2", k, base, "mistralai/mistral-large-2-instruct")
            add("nv-deepseek-v4-flash", k, base, "deepseek-ai/deepseek-v4-flash")
            add("nv-nemotron-super-49b", k, base, "nvidia/llama-3.3-nemotron-super-49b-v1.5")
            add("nv-glm-52", k, base, "z-ai/glm-5.2")
            add("nv-kimi-k26", k, base, "moonshotai/kimi-k2.6")
            add("nv-gpt-oss-120b", k, base, "openai/gpt-oss-120b")
            add("nv-inkling", k, base, "thinkingmachines/inkling")
            add("nv-minimax-m3", k, base, "minimaxai/minimax-m3")
            add("nv-nemotron-ultra-253b", k, base, "nvidia/llama-3.1-nemotron-ultra-253b-v1")
            add("nv-codestral-22b", k, base, "mistralai/codestral-22b-instruct-v0.1")
            add("nv-nemotron-ultra-550b", k, base, "nvidia/nemotron-3-ultra-550b-a55b")
            add("nv-laguna", k, base, "poolside/laguna-xs-2.1")
        }

        // OpenRouter (many models behind one key)
        System.getenv("OPENROUTER_API_KEY")?.let { k ->
            add("openrouter-glm52", k, "https://openrouter.ai/api/v1", "z-ai/glm-5.2")
            add("openrouter-nemotron", k, "https://openrouter.ai/api/v1", "nvidia/nemotron-3-ultra-550b-a55b:free")
        }

        // ZAI
        System.getenv("ZAI_API_KEY")?.let { k ->
            add("zai", k, "https://api.z.ai/api/paas/v4", "glm-5.2")
        }

        // Groq (fast inference)
        System.getenv("GROQ_API_KEY")?.let { k ->
            add("groq", k, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
        }

        // DeepSeek
        System.getenv("DEEPSEEK_API_KEY")?.let { k ->
            add("deepseek", k, "https://api.deepseek.com/v1", "deepseek-chat")
        }

        // Cerebras
        System.getenv("CEREBRAS_API_KEY")?.let { k ->
            add("cerebras", k, "https://api.cerebras.ai/v1", "llama-3.3-70b")
        }

        // OpenAI (if real API key, not ChatGPT auth)
        System.getenv("OPENAI_API_KEY")?.let { k ->
            if (k.startsWith("sk-")) {
                val base = System.getenv("OPENAI_API_BASE") ?: "https://api.openai.com/v1"
                add("openai", k, base, "gpt-4o-mini")
            }
        }

        // Perplexity
        System.getenv("PERPLEXITY_API_KEY")?.let { k ->
            add("perplexity", k, "https://api.perplexity.ai", "llama-3.1-sonar-small-128k-online")
        }

        // XAI / Grok
        System.getenv("XAI_API_KEY")?.let { k ->
            add("xai", k, "https://api.x.ai/v1", "grok-2-latest")
        }

        // Moonshot / Kimi
        System.getenv("MOONSHOT_API_KEY")?.let { k ->
            add("moonshot", k, "https://api.moonshot.cn/v1", "moonshot-v1-32k")
        }

        // MiniMax — native API (m2.5 + m3)
        System.getenv("MINIMAX_API_KEY")?.let { k ->
            add("minimax-m3", k, "https://api.minimax.chat/v1", "MiniMax-M3")
            add("minimax-m25", k, "https://api.minimax.chat/v1", "MiniMax-Text-01")
        }

        return out
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
}
