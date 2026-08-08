package borg.trikeshed.jules

import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toArray
import keymux.EnvVarSource
import keymux.FixedKeySource
import keymux.KeyMux
import modelmux.ModelEntry
import modelmux.ModelMux
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

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
 * The routing/key design is authoritative here: provider selection goes through
 * ModelMux and credential resolution goes through KeyMux. Environment variables
 * are used only to discover which bootstrap bindings exist.
 */
class BrainClient(
    /** If non-null, overrides auto-discovery and uses a single endpoint. */
    apiKey: String? = null,
    base: String = "https://integrate.api.nvidia.com/v1",
    model: String = "poolside/laguna-xs-2.1",
    private val errorSink: BrainErrorSink = DiscardingBrainErrorSink,
) {
    /** One OpenAI-compatible endpoint + the env var that KeyMux resolves. */
    data class EndpointSpec(
        val name: String,
        val envVar: String,
        val base: String,
        val model: String,
    )

    private val endpoints: List<EndpointSpec> = if (apiKey != null) {
        listOf(EndpointSpec("override", "BRAIN_OVERRIDE", base, model))
    } else {
        discoverEndpoints()
    }
    private val endpointByModel: Map<String, EndpointSpec> = endpoints.associateBy { it.model }
    private val keyMux: KeyMux = buildKeyMux(apiKey, model)
    private val modelMux: ModelMux = ModelMux(keyMux) {
        endpoints.forEach { endpoint ->
            model(id = endpoint.model, caps = setOf("chat", "conflict-resolve"), baseUrl = endpoint.base)
        }
    }

    /** Last model id that answered a chat. */
    @Volatile private var lastGoodModelId: String? = endpoints.firstOrNull()?.model

    /** True if at least one provider endpoint was discovered. */
    fun hasEndpoints(): Boolean = endpoints.isNotEmpty()

    /** Non-streaming chat completion with multi-provider failover. */
    suspend fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 256, temperature: Double = 0.2): String {
        if (endpoints.isEmpty()) error("Brain: no provider endpoints discovered")

        var lastError = "all providers exhausted"
        val routed = modelMux.route("conflict-resolve").a
        for (modelId in orderedModelIds(routed)) {
            val endpoint = endpointByModel[modelId] ?: continue
            val session = try {
                modelMux.session(modelId).getOrThrow()
            } catch (t: Throwable) {
                lastError = "Brain ${endpoint.name} session failed: ${t.message}"
                logError(endpoint.name, -1, t.message.orEmpty().take(500))
                continue
            }
            session.activate()
            val body = buildString {
                append("""{"model":${jsonStr(modelId)},"messages":[""")
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
            try {
                val response = try {
                    withTimeout(15_000) {
                        TrikeHtxHttpClient(
                            base = session.baseUrl,
                            defaultHeaders = htxHeaders(*session.authHeaders().toArray()),
                        ).post("/chat/completions", body)
                    }
                } catch (t: HtxHttpException) {
                    lastError = "Brain ${endpoint.name} ${t.status}: ${t.message}"
                    logError(endpoint.name, t.status, t.message.orEmpty().take(500))
                    continue
                } catch (t: Throwable) {
                    // A hung or refused cluster must not abort failover — quotas
                    // are disjoint, so the next model family may answer immediately.
                    lastError = "Brain ${endpoint.name} threw: ${t.message}"
                    logError(endpoint.name, -1, t.message.orEmpty().take(500))
                    continue
                }
                lastGoodModelId = modelId
                return extractContent(response)
            } finally {
                session.drain()
                session.close()
            }
        }
        error(lastError)
    }

    private fun orderedModelIds(routed: Series<ModelEntry>): List<String> {
        val modelIds = (0 until routed.size).map { routed[it].a }
        val preferred = lastGoodModelId ?: return modelIds
        val start = modelIds.indexOf(preferred)
        if (start < 0) return modelIds
        return (0 until modelIds.size).map { offset -> modelIds[(start + offset) % modelIds.size] }
    }

    private fun buildKeyMux(overrideKey: String?, overrideModel: String): KeyMux = KeyMux {
        if (overrideKey != null) {
            bind("llm.$overrideModel.key", FixedKeySource(overrideKey, name = "brain-override"))
        } else {
            endpoints.forEach { endpoint ->
                bind("llm.${endpoint.model}.key", EnvVarSource(endpoint.envVar))
            }
        }
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
                else -> {
                    out.append(c)
                    k++
                }
            }
        }
        error("Brain: unterminated content")
    }

    /** Discover all configured OpenAI-compatible endpoints. */
    private fun discoverEndpoints(): List<EndpointSpec> {
        val out = mutableListOf<EndpointSpec>()
        val seen = mutableSetOf<String>()

        fun add(name: String, envVar: String, base: String, model: String) {
            val key = SystemOperations.default.getenv(envVar)
            val id = "$name:$base:$model"
            if (!key.isNullOrBlank() && seen.add(id)) {
                out.add(EndpointSpec(name, envVar, base.trim(), model.trim()))
            }
        }

        val nvidia = "https://integrate.api.nvidia.com/v1"
        add("nv-deepseek-v4-pro", "NVIDIA_API_KEY", nvidia, "deepseek-ai/deepseek-v4-pro")
        add("nv-nemotron-super-120b", "NVIDIA_API_KEY", nvidia, "nvidia/nemotron-3-super-120b-a12b")
        add("nv-mistral-large-2", "NVIDIA_API_KEY", nvidia, "mistralai/mistral-large-2-instruct")
        add("nv-deepseek-v4-flash", "NVIDIA_API_KEY", nvidia, "deepseek-ai/deepseek-v4-flash")
        add("nv-nemotron-super-49b", "NVIDIA_API_KEY", nvidia, "nvidia/llama-3.3-nemotron-super-49b-v1.5")
        add("nv-glm-52", "NVIDIA_API_KEY", nvidia, "z-ai/glm-5.2")
        add("nv-kimi-k26", "NVIDIA_API_KEY", nvidia, "moonshotai/kimi-k2.6")
        add("nv-gpt-oss-120b", "NVIDIA_API_KEY", nvidia, "openai/gpt-oss-120b")
        add("nv-inkling", "NVIDIA_API_KEY", nvidia, "thinkingmachines/inkling")
        add("nv-minimax-m3", "NVIDIA_API_KEY", nvidia, "minimaxai/minimax-m3")
        add("nv-nemotron-ultra-253b", "NVIDIA_API_KEY", nvidia, "nvidia/llama-3.1-nemotron-ultra-253b-v1")
        add("nv-codestral-22b", "NVIDIA_API_KEY", nvidia, "mistralai/codestral-22b-instruct-v0.1")
        add("nv-nemotron-ultra-550b", "NVIDIA_API_KEY", nvidia, "nvidia/nemotron-3-ultra-550b-a55b")
        add("nv-laguna", "NVIDIA_API_KEY", nvidia, "poolside/laguna-xs-2.1")
        add("openrouter-glm52", "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", "z-ai/glm-5.2")
        add("openrouter-nemotron", "OPENROUTER_API_KEY", "https://openrouter.ai/api/v1", "nvidia/nemotron-3-ultra-550b-a55b:free")
        add("zai", "ZAI_API_KEY", "https://api.z.ai/api/paas/v4", "glm-5.2")
        add("groq", "GROQ_API_KEY", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
        add("deepseek", "DEEPSEEK_API_KEY", "https://api.deepseek.com/v1", "deepseek-chat")
        add("cerebras", "CEREBRAS_API_KEY", "https://api.cerebras.ai/v1", "llama-3.3-70b")
        val openAiBase = SystemOperations.default.getenv("OPENAI_API_BASE") ?: "https://api.openai.com/v1"
        if ((SystemOperations.default.getenv("OPENAI_API_KEY") ?: "").startsWith("sk-")) {
            add("openai", "OPENAI_API_KEY", openAiBase, "gpt-4o-mini")
        }
        add("perplexity", "PERPLEXITY_API_KEY", "https://api.perplexity.ai", "llama-3.1-sonar-small-128k-online")
        add("xai", "XAI_API_KEY", "https://api.x.ai/v1", "grok-2-latest")
        add("moonshot", "MOONSHOT_API_KEY", "https://api.moonshot.cn/v1", "moonshot-v1-32k")
        add("minimax-m3", "MINIMAX_API_KEY", "https://api.minimax.chat/v1", "MiniMax-M3")
        add("minimax-m25", "MINIMAX_API_KEY", "https://api.minimax.chat/v1", "MiniMax-Text-01")
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