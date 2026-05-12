package borg.trikeshed.viewserver

import borg.trikeshed.lib.j
import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.contextOf

/**
 * CouchDB 1.7.2 view server — Node.js binding.
 *
 * Reads JSON-lines from stdin, parses via Confix (JSON → JsContext → reified List),
 * dispatches to [CouchQueryServer], serializes responses to stdout.
 *
 * For CouchDB to use this:
 * ```
 * [query_servers]
 * kotlin = /path/to/kotlin-node-runner viewserver.js
 * ```
 */
fun main() {
    val server = CouchQueryServer(::compileJsFunction)
    val rl = js("require('readline').createInterface({ input: process.stdin, output: process.stdout, terminal: false })")

    rl.on("line", { line: dynamic ->
        try {
            val text = line as CharSequence
            val ctx = contextOf(Syntax.JSON, text.toSeries())
            val reified = Combinators.reify(ctx)

            @Suppress("UNCHECKED_CAST")
            val jsonList = reified as? List<*> ?: run {
                println(JsonSerializer.serialize(CouchResponse.Error("expected JSON array, got: $reified")))
                return@on
            }

            val command = CouchCommand.parse(jsonList) ?: run {
                println(JsonSerializer.serialize(CouchResponse.Error("unknown command")))
                return@on
            }

            val response = server.handle(command)
            println(JsonSerializer.serialize(response))
        } catch (e: dynamic) {
            println(JsonSerializer.serialize(CouchResponse.Error("${e.message}")))
        }
    })
}

// ── JS function compilation ────────────────────────────────────────────

private fun compileJsFunction(source: CharSequence): CompiledFunction {
    val fn: dynamic = try {
        js("eval")("($source)")
    } catch (e: dynamic) {
        throw IllegalArgumentException("Failed to compile: $source — ${e.message}")
    }
    return JsCompiledFunction(fn)
}

private class JsCompiledFunction(private val fn: dynamic) : CompiledFunction {
    override fun map(doc: Map<CharSequence, Any?>, emit: (key: Any?, value: Any?) -> Unit) {
        val emitJs = { k: dynamic, v: dynamic -> emit(k, v) }
        fn(doc, emitJs)
    }

    override fun reduce(sources: List<CharSequence>, values: List<Any?>, rereduce: Boolean): Any? {
        // CouchDB reduce: fn(keys, values, rereduce)
        // For simplicity, use the first source and pass values + rereduce flag
        val reduceFn: dynamic = try {
            js("eval")("(${sources.firstOrNull() ?: "null"})")
        } catch (e: dynamic) { null }
            ?: return null

        val jsValues = values.toTypedArray()
        val result = reduceFn(js("[]"), jsValues, rereduce)
        return result
    }
}

// ── Helpers ────────────────────────────────────────────────────────────

private fun CharSequence.toSeries(): borg.trikeshed.lib.Series<Char> {
    val n = length
    return n j { i: Int -> this[i] }
}
