package modelmux.acp

import borg.trikeshed.lib.*
import borg.trikeshed.lib.`▶`

// ═══════════════════════════════════════════
// ACP type algebra — everything is Join/Series
// ═══════════════════════════════════════════

typealias AcpVersion = String
typealias AcpAction  = String      // "chat" | "complete" | "embed" | "stream"

/** ACP header: protocol metadata */
typealias AcpMeta = Join<AcpVersion, Join<AcpAction, Series<Join<String, String>>>>

val AcpMeta.version: AcpVersion get() = a
val AcpMeta.action: AcpAction   get() = b.a
val AcpMeta.headers: Series<Join<String, String>> get() = b.b

/** Chat message in ACP: role j content */
typealias AcpMessage = Join<String, String>  // role j text

/** Tool/function declaration: name j parameter-schema-JSON */
typealias AcpTool = Join<String, String>

/** ACP request body: messages j tools */
typealias AcpRequestBody = Join<Series<AcpMessage>, Series<AcpTool>>

/** ACP request envelope */
typealias AcpRequest = Join<AcpMeta, AcpRequestBody>

/** ACP response chunk (streaming): delta j usage */
typealias AcpUsage = Join<Int, Int>          // prompt_tokens j completion_tokens
typealias AcpChunk = Join<String, AcpUsage>  // text_delta j usage

/** ACP response (non-streaming): full_text j usage */
typealias AcpResponse = Join<String, AcpUsage>

/** ACP model card — capability advertisement */
typealias AcpCapability = String           // "chat", "stream", "tools", "vision", "embed"

/**
 * Rich-media capability dimensions (plan step 6): DECLARED now so routes,
 * presets, and contracts can name them; model bindings stay deferred —
 * env-gated exactly like [borg.trikeshed.jules.BrainClient] endpoint
 * discovery. A card not bound to a rich-media lane simply never advertises
 * these; nothing else in the mux changes shape.
 *
 *  - video.gen  : text→video generation
 *  - audio.gen  : text→speech / text→audio generation
 *  - image.gen  : text→image generation
 *  - doc.ingest : document → structured text (Tika/OCR lane)
 */
object AcpRichMedia {
    const val VIDEO_GEN = "video.gen"
    const val AUDIO_GEN = "audio.gen"
    const val IMAGE_GEN = "image.gen"
    const val DOC_INGEST = "doc.ingest"

    /** The closed set, for contract routes and validation. */
    val all: Set<String> = setOf(VIDEO_GEN, AUDIO_GEN, IMAGE_GEN, DOC_INGEST)

    /**
     * Binding gate: a rich-media capability is routable only when its
     * enabling environment is present (same discipline as provider keys —
     * declare the dimension, defer the lane). envPresent is injected, not
     * read here, keeping commonMain free of platform env access.
     */
    fun isBound(cap: String, envPresent: (String) -> Boolean): Boolean = when (cap) {
        VIDEO_GEN -> envPresent("VIDEO_GEN_ENDPOINT")
        AUDIO_GEN -> envPresent("AUDIO_GEN_ENDPOINT")
        IMAGE_GEN -> envPresent("IMAGE_GEN_ENDPOINT")
        DOC_INGEST -> true // the Tika lane ships in-process
        else -> false
    }
}
typealias AcpModelCard = Join<String, Join<Series<AcpCapability>, AcpMeta>>
// model_id j (capabilities j default_meta)

val AcpModelCard.id:   String               get() = a
val AcpModelCard.caps: Series<AcpCapability> get() = b.a
val AcpModelCard.meta: AcpMeta              get() = b.b

/**
 * The provider id a catalog ingester tagged this card with, if any.
 *
 * ModelMux resolves keys by MODEL id (`llm.<modelId>.key`); a real credential
 * pool (Hermes', for one) is keyed by PROVIDER. `providerTag` is the seam:
 * when present, [modelmux.ModelMux.session] tries `llm.<providerTag>.key`
 * before the per-model lookup, so one provider credential serves every model
 * routed through it instead of needing one key entry per model id.
 */
val AcpModelCard.providerTag: String?
    get() {
        val headers = meta.headers
        for (i in 0 until headers.size) {
            val pair = headers[i]
            if (pair.a == "provider") return pair.b
        }
        return null
    }

/**
 * What the PROVIDER calls this model, when that differs from the card's id.
 *
 * A card id has two jobs that only coincide by luck: it is the local routing key
 * (`ModelMux.session` scans for it, so it must be unique across the catalog) and
 * it is the string sent as `"model"` on the wire (so it must be exactly what the
 * provider recognises). They collide the moment two providers serve the same
 * model: one of the two cards has to take a qualified id like
 * `openrouter/z-ai/glm-5.2` to stay reachable, and sending THAT to the provider
 * earns `400 … is not a valid model ID`.
 *
 * So the qualified name stays local and the wire name rides here. Null means the
 * two coincide, which is the common case. Stamped into the card's metadata header
 * bag exactly as [providerTag] is — that bag is catalog metadata and is never
 * transmitted (the HTTP headers come from `LlmSession.authHeaders()`), so nothing
 * leaks onto the request.
 */
val AcpModelCard.wireModel: String?
    get() {
        val headers = meta.headers
        for (i in 0 until headers.size) {
            val pair = headers[i]
            if (pair.a == "wire_model") return pair.b
        }
        return null
    }

/** The string to send as `"model"`: the provider's name for it, else the card id. */
val AcpModelCard.wireName: String get() = wireModel ?: id

// ── ACP codec: AcpRequest → HTTP request bytes ──

object AcpCodec {

    fun encodeRequest(
        req: AcpRequest,
        maxTokens: Int? = null,
        temperature: Double? = null,
    ): String = buildString {
        val (meta, body) = req
        val (msgs, tools) = body
        append("{")
        append("\"model\":\"${meta.a}\",")   // version field reused as model id at wire level
        append("\"messages\":[")
        msgs.`▶`.forEachIndexed { i, (role, content) ->
            if (i > 0) append(",")
            append("{\"role\":\"$role\",\"content\":${jsonStr(content)}}")
        }
        append("]")
        if (tools.size > 0) {
            append(",\"tools\":[")
            tools.`▶`.forEachIndexed { i, (name, schema) ->
                if (i > 0) append(",")
                append("{\"type\":\"function\",\"function\":{\"name\":\"$name\",\"parameters\":$schema}}")
            }
            append("]")
        }
        if (maxTokens != null) append(",\"max_tokens\":$maxTokens")
        if (temperature != null) append(",\"temperature\":$temperature")
        // stream flag
        if (meta.b.a == "stream") append(",\"stream\":true")
        append("}")
    }

    fun parseChunk(raw: String): AcpChunk? {
        if (raw.startsWith("[DONE]") || raw.isBlank()) return null
        val json = raw.trim().removePrefix("data:").trim()
        if (!json.startsWith("{")) return null
        val delta = extractDelta(json)
        val usage = extractUsage(json)
        return delta j usage
    }

    fun parseResponse(raw: String): AcpResponse {
        val content = extractContent(raw)
        val usage   = extractUsage(raw)
        return content j usage
    }

    /**
     * A JSON string literal. Backslash, quote and newline are not the only
     * characters that must not ride raw inside one: a tab or a carriage
     * return (both ordinary in a pasted transcript) makes the request body
     * malformed JSON, which providers answer with a 400 that reads like a
     * model failure. Everything below U+0020 is escaped.
     */
    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c.code < 0x20) {
                append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else append(c)
        }
        append('"')
    }

    private fun extractDelta(json: String): String {
        val i = json.indexOf("\"delta\"")
        if (i < 0) return ""
        val ci = json.indexOf("\"content\"", i)
        if (ci < 0) return ""
        return extractQuoted(json, ci + "\"content\"".length)
    }

    /**
     * The assistant's text, or "" when the reply carries none.
     *
     * This used to be `indexOf("\"content\"")` followed by "take the next quoted
     * string", which is only correct when the value IS a quoted string. When a
     * provider sends `"content":null` — routine for a reasoning model, a refusal,
     * or a length-truncated reply — the scan sailed past the null and returned the
     * next quoted token in the document, which is the following KEY. A live
     * openrouter reply produced the answer `refusal` that way: not empty, not an
     * error, just a plausible-looking word the model never said. A parser that
     * FABRICATES content is worse than one that returns nothing, because nothing
     * downstream can tell the difference.
     *
     * So the value is now inspected before it is trusted:
     *  - a quoted string is the answer (empty string → keep looking, a later
     *    `content` may hold the real text);
     *  - `[` is an Anthropic-style block array — the first `"text"` inside it is
     *    the answer;
     *  - `null`, a number, or an object is NOT text: skip to the next candidate.
     *
     * Several keys named `content` legitimately appear in one document (the echoed
     * request, per-choice messages), hence the loop rather than a single hit.
     */
    /**
     * Index of [key] where it is used as a KEY — followed by `:` — at or after
     * [from], else -1. A plain `indexOf` also matches the same token used as a
     * VALUE, which is how `"type":"text"` was mistaken for the `"text"` field and
     * returned the answer `text`.
     */
    private fun indexOfKey(json: String, key: String, from: Int): Int {
        var f = from
        while (true) {
            val k = json.indexOf(key, f)
            if (k < 0) return -1
            var i = k + key.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') return k
            f = k + 1
        }
    }

    private fun extractContent(json: String): String {
        var from = 0
        val key = "\"content\""
        while (true) {
            val ci = indexOfKey(json, key, from)
            if (ci < 0) return ""
            from = ci + key.length
            var i = from
            while (i < json.length && json[i].isWhitespace()) i++
            i++ // the ':' — indexOfKey guaranteed it
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) return ""
            when (json[i]) {
                '"' -> {
                    val s = extractQuoted(json, i)
                    if (s.isNotEmpty()) return s
                }
                '[' -> {
                    // Content blocks: [{"type":"text","text":"…"}]
                    val t = indexOfKey(json, "\"text\"", i)
                    if (t >= 0) {
                        val s = extractQuoted(json, t + "\"text\"".length)
                        if (s.isNotEmpty()) return s
                    }
                }
                // null / number / object — not the assistant's text.
                else -> {}
            }
        }
    }

    private fun extractUsage(json: String): AcpUsage {
        val pi = json.indexOf("\"prompt_tokens\"")
        val ci = json.indexOf("\"completion_tokens\"")
        val p = if (pi >= 0) json.substring(pi).substringAfter(':').substringBefore(',').substringBefore('}').trim().toInt() else 0
        val c = if (ci >= 0) json.substring(ci).substringAfter(':').substringBefore(',').substringBefore('}').trim().toInt() else 0
        return p j c
    }

    /**
     * The quoted string starting at the first `"` at or after [from], with its
     * JSON escapes DECODED.
     *
     * The escape table is not decoration: a completion's content arrives as a
     * JSON string, so every newline in it is on the wire as `\n`. Skipping the
     * backslash and taking the next character literally (the shape this used
     * to have) silently rewrote `\n` to the letter `n`, `\t` to `t`, and
     * `é` to `u00e9` — every multi-line answer this daemon has ever read
     * came back as one unbroken line. `\"` and `\\` happened to survive that
     * shape, which is why it looked correct. Terminating on an unescaped `"`
     * still works because an escaped quote is consumed inside the loop.
     */
    private fun extractQuoted(s: String, from: Int): String {
        var i = from
        while (i < s.length && s[i] != '"') i++
        if (i >= s.length) return ""
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            val c = s[i]
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= s.length) { i++; continue }
            when (val e = s[i + 1]) {
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (hex != null) { sb.append(hex.toChar()); i += 6 } else { sb.append(e); i += 2 }
                }
                // An unknown escape is not ours to reinterpret: keep the
                // character, drop the backslash, exactly as before.
                else -> { sb.append(e); i += 2 }
            }
        }
        return sb.toString()
    }
}
