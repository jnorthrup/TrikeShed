/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.jules

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * BrainClient — the real flywheel brain.
 *
 * OpenAI-compatible chat completions over HTTPS. Default endpoint is NVIDIA NIM
 * (Laguna XS 2.1). The flywheel's [FlywheelDriver.buildAnswer] calls this to
 * answer Jules sessions with project conventions as the system prompt.
 *
 * The brain must fire a real model. A string template is not a brain.
 *
 * @param apiKey NIM API key (NVIDIA_API_KEY env var)
 * @param base endpoint base URL (default: NVIDIA NIM)
 * @param model model id (default: Laguna XS 2.1)
 */
class BrainClient(
    private val apiKey: String,
    private val base: String = "https://integrate.api.nvidia.com/v1",
    private val model: String = "poolside/laguna-xs-2.1",
) {
    private val http: HttpClient = HttpClient.newHttpClient()

    /** Non-streaming chat completion. Returns the assistant's message text. */
    fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 256, temperature: Double = 0.2): String {
        val body = buildString {
            append("""{"model":${jsonStr(model)},"messages":[""")
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
            .uri(URI.create("$base/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 400)
            error("Brain API ${resp.statusCode()}: ${resp.body().take(400)}")
        return extractContent(resp.body())
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
