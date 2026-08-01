package borg.trikeshed.jules

import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock

/** Target adapter for the optional durable brain-error audit trail. */
fun interface BrainErrorSink {
    fun append(entry: String)
}

object DiscardingBrainErrorSink : BrainErrorSink {
    override fun append(entry: String) = Unit
}

/**
 * BrainClient — the real flywheel brain.
 *
 * OpenAI-compatible chat completions over common reactor HTX with multi-provider
 * failover. The flywheel's [FlywheelDriver.buildAnswer] calls this to answer
 * Jules sessions with project conventions as the system prompt.
 *
 * The brain must fire a real model. A string template is not a brain. HTTP is
 * always [TrikeHtxHttpClient]; target code supplies NIO/TLS and, optionally,
 * a [BrainErrorSink] for durable diagnostics.
 */
class BrainClient(
    /** If non-null, overrides auto-discovery and uses a single endpoint. */
    apiKey: String? = null,
    base: String = "https://integrate.api.nvidia.com/v1",
    model: String = "poolside/laguna-xs-2.1",
    private val errorSink: BrainErrorSink = DiscardingBrainErrorSink,
) {
    /** One OpenAI-compatible endpoint with credentials and a default model. */
    data class Endpoint(
        val name: String,
        val apiKey: String,
        val base: String,
        val model: String,
    )

    private val endpoints: Series<Endpoint> = if (apiKey != null) {
        1 j { _: Int -> Endpoint("override", apiKey, base, model) }
    } else {
        discoverEndpoints()
    }

    /** Last endpoint that answered a chat. */
    @Volatile private var lastGood: Int = 0

    /** True if at least one provider endpoint was discovered. */
    fun hasEndpoints(): Boolean = endpoints.size != 0

    /** Non-streaming chat completion with multi-provider failover. */
    suspend fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 256, temperature: Double = 0.2): String {
        if (endpoints.size == 0) error("Brain: no provider endpoints discovered")

        var lastError = "all providers exhausted"
        val start = lastGood.coerceIn(0, endpoints.size - 1)
        for (offset in 0 until endpoints.size) {
            val idx = (start + offset) % endpoints.size
            val ep = endpoints[idx]
            val body = buildString {
                append("""{"model":${jsonStr(ep.model)},"messages":[""")
                messages.forEachIndexed { index, (role, content) ->
                    if (index > 0) append(',')
                    append("""{"role":${jsonStr(role)},"content":${jsonStr(content)}}""")
                }
                append("],")
                append("\"max_tokens\":$maxTokens,")
                append("\"temperature\":$temperature,")
                append("\"top_p\":0.9")
                append('}')
            }
            val response = try {
                withTimeout(15_000) {
                    TrikeHtxHttpClient(
                        base = ep.base,
                        defaultHeaders = htxHeaders("Authorization" j "Bearer ${ep.apiKey}"),
                    ).post("/chat/completions", body)
                }
            } catch (t: HtxHttpException) {
                lastError = "Brain ${ep.name} ${t.status}: ${t.message}"
                logError(ep.name, t.status, t.message.orEmpty().take(500))
                continue
            } catch (t: Throwable) {
                // A hung or refused cluster must not abort failover — quotas
                // are disjoint, so the next model family may answer immediately.
                lastError = "Brain ${ep.name} threw: ${t.message}"
                logError(ep.name, -1, t.message.orEmpty().take(500))
                continue
            }
            lastGood = idx
            return extractContent(response)
        }
        error(lastError)
    }

    private fun logError(provider: String, statusCode: Int, bodySnippet: String) {
        val entry = buildString {
            append("{\"t\":")
            append(Clock.System.now().toEpochMilliseconds())
            append(",\"provider\":")
            append(jsonStr(provider))
            append(",\"status\":")
            append(statusCode)
            append(",\"body\":")
            append(jsonStr(bodySnippet))
            append('}')
        }
        errorSink.append(entry)
    }

    /** Pull choices[0].message.content out of the OpenAI-compatible JSON. */
    private fun extractContent(json: String): String {
        val key = """"content""""
        val i = json.indexOf(key)
        if (i < 0) error("Brain: no content field in response: ${json.take(200)}")
        var j = json.indexOf('"', i + key.length).also { if (it < 0) error("Brain: malformed content") }
        j = json.indexOf('"', j).let { if (it < 0) error("Brain: missing content open quote"); it }
        val out = StringBuilder()
        var k = j + 1
        while (k < json.length) {
            val c = json[k]
            when {
                c == '\\' && k + 1 < json.length -> {
                    when (val escaped = json[k + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'r' -> out.append('\r')
                        else -> out.append(escaped)
                    }
                    k += 2
                }
                c == '"' -> return out.toString()
                else -> { out.append(c); k++ }
            }
        }
        error("Brain: unterminated content")
    }

    /** Discover all configured OpenAI-compatible endpoints. */
    private fun discoverEndpoints(): Series<Endpoint> {
        val out = mutableListOf<Endpoint>()
        val seen = mutableSetOf<String>()

        fun add(name: String, key: String, base: String, model: String) {
            val id = "$name:$base:$model"
            if (key.isNotBlank() && seen.add(id)) {
                out.add(Endpoint(name, key.trim(), base.trim(), model.trim()))
            }
        }

        SystemOperations.default.getenv("NVIDIA_API_KEY")?.let { key ->
            val nvidia = "https://integrate.api.nvidia.com/v1"
            add("nv-deepseek-v4-pro", key, nvidia, "deepseek-ai/deepseek-v4-pro")
            add("nv-nemotron-super-120b", key, nvidia, "nvidia/nemotron-3-super-120b-a12b")
            add("nv-mistral-large-2", key, nvidia, "mistralai/mistral-large-2-instruct")
            add("nv-deepseek-v4-flash", key, nvidia, "deepseek-ai/deepseek-v4-flash")
            add("nv-nemotron-super-49b", key, nvidia, "nvidia/llama-3.3-nemotron-super-49b-v1.5")
            add("nv-glm-52", key, nvidia, "z-ai/glm-5.2")
            add("nv-kimi-k26", key, nvidia, "moonshotai/kimi-k2.6")
            add("nv-gpt-oss-120b", key, nvidia, "openai/gpt-oss-120b")
            add("nv-inkling", key, nvidia, "thinkingmachines/inkling")
            add("nv-minimax-m3", key, nvidia, "minimaxai/minimax-m3")
            add("nv-nemotron-ultra-253b", key, nvidia, "nvidia/llama-3.1-nemotron-ultra-253b-v1")
            add("nv-codestral-22b", key, nvidia, "mistralai/codestral-22b-instruct-v0.1")
            add("nv-nemotron-ultra-550b", key, nvidia, "nvidia/nemotron-3-ultra-550b-a55b")
            add("nv-laguna", key, nvidia, "poolside/laguna-xs-2.1")
        }
        SystemOperations.default.getenv("OPENROUTER_API_KEY")?.let { key ->
            add("openrouter-glm52", key, "https://openrouter.ai/api/v1", "z-ai/glm-5.2")
            add("openrouter-nemotron", key, "https://openrouter.ai/api/v1", "nvidia/nemotron-3-ultra-550b-a55b:free")
        }
        SystemOperations.default.getenv("ZAI_API_KEY")?.let { key ->
            add("zai", key, "https://api.z.ai/api/paas/v4", "glm-5.2")
        }
        SystemOperations.default.getenv("GROQ_API_KEY")?.let { key ->
            add("groq", key, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
        }
        SystemOperations.default.getenv("DEEPSEEK_API_KEY")?.let { key ->
            add("deepseek", key, "https://api.deepseek.com/v1", "deepseek-chat")
        }
        SystemOperations.default.getenv("CEREBRAS_API_KEY")?.let { key ->
            add("cerebras", key, "https://api.cerebras.ai/v1", "llama-3.3-70b")
        }
        SystemOperations.default.getenv("OPENAI_API_KEY")?.let { key ->
            if (key.startsWith("sk-")) {
                add("openai", key, SystemOperations.default.getenv("OPENAI_API_BASE") ?: "https://api.openai.com/v1", "gpt-4o-mini")
            }
        }
        SystemOperations.default.getenv("PERPLEXITY_API_KEY")?.let { key ->
            add("perplexity", key, "https://api.perplexity.ai", "llama-3.1-sonar-small-128k-online")
        }
        SystemOperations.default.getenv("XAI_API_KEY")?.let { key ->
            add("xai", key, "https://api.x.ai/v1", "grok-2-latest")
        }
        SystemOperations.default.getenv("MOONSHOT_API_KEY")?.let { key ->
            add("moonshot", key, "https://api.moonshot.cn/v1", "moonshot-v1-32k")
        }
        SystemOperations.default.getenv("MINIMAX_API_KEY")?.let { key ->
            add("minimax-m3", key, "https://api.minimax.chat/v1", "MiniMax-M3")
            add("minimax-m25", key, "https://api.minimax.chat/v1", "MiniMax-Text-01")
        }
        return out.toSeries()
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
