package modelmux.acp

import borg.trikeshed.lib.*

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
typealias AcpModelCard = Join<String, Join<Series<AcpCapability>, AcpMeta>>
// model_id j (capabilities j default_meta)

val AcpModelCard.id:   String               get() = a
val AcpModelCard.caps: Series<AcpCapability> get() = b.a
val AcpModelCard.meta: AcpMeta              get() = b.b

// ── ACP codec: AcpRequest → HTTP request bytes ──

object AcpCodec {

    fun encodeRequest(req: AcpRequest): String = buildString {
        val (meta, body) = req
        val (msgs, tools) = body
        append("{")
        append("\"model\":\"${meta.a}\",")   // version field reused as model id at wire level
        append("\"messages\":[")
        msgs.view.forEachIndexed { i, (role, content) ->
            if (i > 0) append(",")
            append("{\"role\":\"$role\",\"content\":${jsonStr(content)}}")
        }
        append("]")
        if (tools.size > 0) {
            append(",\"tools\":[")
            tools.view.forEachIndexed { i, (name, schema) ->
                if (i > 0) append(",")
                append("{\"type\":\"function\",\"function\":{\"name\":\"$name\",\"parameters\":$schema}}")
            }
            append("]")
        }
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

    private fun jsonStr(s: String): String = "\"" +
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun extractDelta(json: String): String {
        val i = json.indexOf("\"delta\"")
        if (i < 0) return ""
        val ci = json.indexOf("\"content\"", i)
        if (ci < 0) return ""
        return extractQuoted(json, ci + "\"content\"".length)
    }

    private fun extractContent(json: String): String {
        val ci = json.indexOf("\"content\"")
        if (ci < 0) return ""
        return extractQuoted(json, ci + "\"content\"".length)
    }

    private fun extractUsage(json: String): AcpUsage {
        val pi = json.indexOf("\"prompt_tokens\"")
        val ci = json.indexOf("\"completion_tokens\"")
        val p = if (pi >= 0) json.substring(pi).substringAfter(':').substringBefore(',').substringBefore('}').trim().toInt() else 0
        val c = if (ci >= 0) json.substring(ci).substringAfter(':').substringBefore(',').substringBefore('}').trim().toInt() else 0
        return p j c
    }

    private fun extractQuoted(s: String, from: Int): String {
        var i = from
        while (i < s.length && s[i] != '"') i++
        if (i >= s.length) return ""
        i++ // skip opening quote
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < s.length) { i++; sb.append(s[i]) } else sb.append(s[i])
            i++
        }
        return sb.toString()
    }
}
