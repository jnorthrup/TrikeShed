package borg.trikeshed.parse.json

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries

/**
 * Static-link friendly JSON facade for Dreamer and other JS/Wasm consumers.
 *
 * The parser itself keeps the existing binary64-aware reification behavior,
 * while this wrapper gives callers a stable entrypoint for parsing, indexing,
 * and lightweight jq/xpath-style path queries.
 */
object JsonSupport {
    fun parse(text: String): Any? = JsonParser.reify(text.toSeries())

    fun index(
        text: String,
        depths: MutableList<Int>? = null,
        takeFirst: Int? = null,
    ): JsElement = JsonParser.index(text.toSeries(), depths, takeFirst)

    fun pathOf(vararg steps: Any?): JsPath = steps.asList().toJsPath

    fun query(
        text: String,
        path: JsPath,
        reifyResult: Boolean = true,
        depths: MutableList<Int>? = null,
    ): Any? {
        if (path.size == 0) return parse(text)
        val src = text.toSeries()
        return JsonParser.jsPath(JsonParser.index(src, depths) j src, path, reifyResult, depths)
    }

    fun query(
        text: String,
        path: List<*>,
        reifyResult: Boolean = true,
        depths: MutableList<Int>? = null,
    ): Any? = query(text, path.toJsPath, reifyResult, depths)

    fun query(
        text: String,
        query: String,
        reifyResult: Boolean = true,
        depths: MutableList<Int>? = null,
    ): Any? = query(text, parseQueryPath(query), reifyResult, depths)

    private fun parseQueryPath(query: String): JsPath {
        val steps = mutableListOf<Any?>()
        val segment = StringBuilder()

        fun flushSegment() {
            val token = segment.toString().trim()
            if (token.isNotEmpty() && token != "$") {
                steps.add(token.toIntOrNull() ?: token)
            }
            segment.setLength(0)
        }

        var i = 0
        while (i < query.length) {
            when (val ch = query[i]) {
                '.', '/' -> flushSegment()
                '[' -> {
                    flushSegment()
                    val close = query.indexOf(']', i + 1)
                    require(close > i + 1) {
                        "unclosed path segment in query: $query"
                    }

                    val raw = query.substring(i + 1, close).trim()
                    when {
                        raw.isEmpty() -> Unit
                        raw.startsWith('"') && raw.endsWith('"') && raw.length >= 2 -> {
                            steps.add(raw.substring(1, raw.length - 1))
                        }
                        raw.startsWith('\'') && raw.endsWith('\'') && raw.length >= 2 -> {
                            steps.add(raw.substring(1, raw.length - 1))
                        }
                        else -> raw.toIntOrNull()?.let(steps::add) ?: steps.add(raw)
                    }
                    i = close
                }
                '$' -> if (segment.isEmpty() && steps.isEmpty()) {
                    // Allow JSONPath-style root markers without affecting the path.
                } else {
                    segment.append(ch)
                }
                else -> if (!ch.isWhitespace() || segment.isNotEmpty()) segment.append(ch)
            }
            i += 1
        }

        flushSegment()
        return steps.toJsPath
    }
}