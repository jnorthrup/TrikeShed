package borg.trikeshed.agent.modelmux

import borg.trikeshed.agent.keymux.OAuth
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*

/**
 * The chat dialect a destination speaks, named as Hermes names its api_mode. The mux's own request is OpenAI-shaped; a
 * dialect rewrites it on the way out and the reply on the way back, so receipts, the cache and the decoders see one shape.
 * Text chat only and not streamed: a request that carries tools or a response format is refused, and `thinking` and `stream`
 * are not carried; `stream()` still posts chat completions.
 */
enum class Wire(val apiMode: String, val path: String) {
    CHAT("chat_completions", "/chat/completions"),
    MESSAGES("anthropic_messages", "/v1/messages"),
    RESPONSES("codex_responses", "/responses");

    fun url(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return (if (this == MESSAGES) base.removeSuffix("/v1") else base) + path
    }

    /** Content-Type rides with the credential headers. A Console key is x-api-key; a Claude Code bearer wears Claude Code's identity. */
    fun headers(key: String?): List<Join<String, String>> = if (key == null) listOf("Content-Type" j JSON) else when (this) {
        CHAT -> listOf("Authorization" j "Bearer $key", "Content-Type" j JSON)
        MESSAGES ->
            if (OAuth.isClaudeBearer(key)) listOf(
                "Authorization" j "Bearer $key", "Content-Type" j JSON, "anthropic-version" j ANTHROPIC_VERSION,
                "anthropic-beta" j (COMMON_BETAS + OAUTH_BETAS).joinToString(","),
                "user-agent" j "claude-code/${OAuth.claudeCodeVersion} (external, cli)", "x-app" j "cli",
            ) else listOf("x-api-key" j key, "Content-Type" j JSON, "anthropic-version" j ANTHROPIC_VERSION)
        RESPONSES -> {
            val auth = OAuth.claims(key)?.get("https://api.openai.com/auth") as? Map<*, *>
            listOfNotNull(
                "Authorization" j "Bearer $key", "Content-Type" j JSON, "Accept" j "text/event-stream", "originator" j "trikeshed",
                (auth?.get("chatgpt_account_id") as? String)?.takeIf { it.isNotEmpty() }?.let { "ChatGPT-Account-ID" j it },
                ((auth?.get("chatgpt_data_residency") ?: auth?.get("chatgpt_compute_residency")) as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { "x-openai-internal-codex-residency" j it },
            )
        }
    }

    /** The mux's OpenAI-shaped [openai] request as this dialect's body. */
    fun request(openai: String, key: String?): String {
        if (this == CHAT) return openai
        val bearer = key != null && OAuth.isClaudeBearer(key)
        val req = reifyMap(openai)
        require("tools" !in req && "response_format" !in req) { "$apiMode carries plain chat only: no tools, no response format" }
        val rows = (req["messages"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        val system = rows.filter { it["role"] == "system" }.joinToString("\n\n") { it["content"].toString() }
        val turns = rows.filter { it["role"] != "system" }
        return jsonOf(
            if (this == MESSAGES) buildMap<String, Any?> {
                put("model", req["model"])
                put("max_tokens", (req["max_tokens"] as? Number)?.toInt() ?: DEFAULT_MAX_TOKENS)
                // Without the Claude Code line a bearer is answered 429 (Hermes, #114967).
                if (bearer) put("system", listOfNotNull(textBlock(CLAUDE_CODE), system.takeIf { it.isNotEmpty() }?.let(::textBlock)))
                else if (system.isNotEmpty()) put("system", system)
                put("messages", turns.map { mapOf("role" to it["role"], "content" to it["content"].toString()) })
                (req["temperature"] as? Number)?.takeIf { takesSampling(req["model"].toString()) }?.let { put("temperature", it) }
            } else mapOf(
                // The Codex backend takes instructions, store=false and a stream; Hermes sends it neither temperature nor max_output_tokens.
                "model" to req["model"],
                "instructions" to system.ifEmpty { CODEX_INSTRUCTIONS },
                "input" to turns.map {
                    mapOf(
                        "type" to "message", "role" to it["role"],
                        "content" to listOf(mapOf("type" to if (it["role"] == "assistant") "output_text" else "input_text", "text" to it["content"].toString())),
                    )
                },
                "store" to false, "stream" to true,
            ),
        )
    }

    /** This dialect's reply [raw] as an OpenAI chat completion; a stream that failed in-band is an exception. */
    fun reply(raw: String): String = when (this) {
        CHAT -> raw
        MESSAGES -> messagesReply(raw)
        RESPONSES -> responsesReply(raw)
    }

    private fun textBlock(text: String) = mapOf("type" to "text", "text" to text)

    private fun completion(id: Any?, model: Any?, text: String, finish: String, input: Int, output: Int, extra: Map<String, Any?>) = jsonOf(
        mapOf(
            "id" to id, "object" to "chat.completion", "model" to model,
            "choices" to listOf(mapOf("index" to 0, "message" to mapOf("role" to "assistant", "content" to text), "finish_reason" to finish)),
            "usage" to (mapOf("prompt_tokens" to input, "completion_tokens" to output, "total_tokens" to input + output) + extra),
        ),
    )

    private fun messagesReply(raw: String): String {
        val reply = reifyMap(raw)
        val usage = reply["usage"] as? Map<*, *>
        fun count(key: String) = (usage?.get(key) as? Number)?.toInt() ?: 0
        val read = count("cache_read_input_tokens")
        val write = count("cache_creation_input_tokens")
        val text = (reply["content"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["type"] == "text" }.joinToString("") { it["text"].toString() }
        val finish = when (val stop = reply["stop_reason"]) { "end_turn", "stop_sequence" -> "stop"; "max_tokens" -> "length"; "tool_use" -> "tool_calls"; else -> stop.toString() }
        // Claude thinks before it writes and the thinking counts against max_tokens: a reply that is all thinking is no answer.
        if (text.isEmpty()) throw ModelHttpFailure(502, "$apiMode answered no text (stop_reason ${reply["stop_reason"]}); thinking can use the whole max_tokens")
        return completion(
            reply["id"], reply["model"], text, finish, count("input_tokens") + read + write, count("output_tokens"),
            mapOf("cache_read_input_tokens" to read, "cache_creation_input_tokens" to write),
        )
    }

    private fun responsesReply(raw: String): String {
        val items = StringBuilder()
        val deltas = StringBuilder()
        var terminal: Map<*, *>? = null
        for (line in raw.lineSequence()) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            val event = runCatching { reifyMap(data) }.getOrNull() ?: continue
            when (event["type"]) {
                "response.output_text.delta" -> deltas.append(event["delta"].toString())
                "response.output_item.done" -> (event["item"] as? Map<*, *>)?.takeIf { it["type"] == "message" }
                    ?.let { item -> (item["content"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().filter { it["type"] == "output_text" }.forEach { items.append(it["text"].toString()) } }
                "response.completed", "response.incomplete" -> terminal = event["response"] as? Map<*, *>
                "response.failed", "error" -> throw ModelHttpFailure(502, "$apiMode stream failed: ${jsonOf((event["response"] as? Map<*, *>)?.get("error") ?: event["error"] ?: event).take(300)}")
            }
        }
        checkNotNull(terminal) { "$apiMode stream ended without a terminal event" }
        val usage = terminal["usage"] as? Map<*, *>
        val cached = ((usage?.get("input_tokens_details") as? Map<*, *>)?.get("cached_tokens") as? Number)?.toInt() ?: 0
        val text = items.toString().ifEmpty { deltas.toString() }
        if (text.isEmpty()) throw ModelHttpFailure(502, "$apiMode answered no text (status ${terminal["status"]}); reasoning can use the whole token budget")
        return completion(
            terminal["id"], terminal["model"], text,
            if (terminal["status"] == "incomplete") "length" else "stop",
            (usage?.get("input_tokens") as? Number)?.toInt() ?: 0, (usage?.get("output_tokens") as? Number)?.toInt() ?: 0,
            mapOf("cached_tokens" to cached),
        )
    }

    companion object {
        private const val JSON = "application/json"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val DEFAULT_MAX_TOKENS = 4096
        private const val CLAUDE_CODE = "You are Claude Code, Anthropic's official CLI for Claude."
        private const val CODEX_INSTRUCTIONS = "You are a helpful assistant."
        private val COMMON_BETAS = listOf("interleaved-thinking-2025-05-14", "fine-grained-tool-streaming-2025-05-14")
        private val OAUTH_BETAS = listOf("claude-code-20250219", "oauth-2025-04-20")

        /** Claude up to 4.6 takes `temperature`; every later or unknown Claude answers 400 "`temperature` is deprecated for this model". */
        private val SAMPLING_FAMILIES = listOf(
            "claude-3", "claude-opus-4-0", "claude-opus-4.0", "claude-opus-4-1", "claude-opus-4.1", "claude-sonnet-4-0", "claude-sonnet-4.0",
            "claude-opus-4-2025", "claude-sonnet-4-2025", "claude-opus-4-5", "claude-opus-4.5", "claude-sonnet-4-5", "claude-sonnet-4.5",
            "claude-haiku-4-5", "claude-haiku-4.5", "claude-opus-4-6", "claude-opus-4.6", "claude-sonnet-4-6", "claude-sonnet-4.6",
        )

        private fun takesSampling(model: String) = SAMPLING_FAMILIES.any { it in model.lowercase() }

        /** The dialect is the destination's: api.anthropic.com speaks Messages, chatgpt.com/backend-api/codex speaks Responses. */
        fun of(baseUrl: String): Wire {
            val rest = baseUrl.substringAfter("://").trimEnd('/')
            val host = rest.substringBefore('/').substringBefore(':')
            val path = rest.substringAfter('/', "")
            return when {
                host == "api.anthropic.com" -> MESSAGES
                host == "chatgpt.com" && path.startsWith("backend-api/codex") -> RESPONSES
                else -> CHAT
            }
        }
    }
}
