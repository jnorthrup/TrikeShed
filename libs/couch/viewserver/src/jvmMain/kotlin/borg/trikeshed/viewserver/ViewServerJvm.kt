package borg.trikeshed.viewserver

import borg.trikeshed.lib.j
import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.contextOf

/**
 * CouchDB 1.7.2 view server — JVM stdin/stdout binding.
 *
 * Reads JSON-lines from stdin, dispatches to [CouchQueryServer],
 * writes responses to stdout.
 *
 * Useful for local testing without CouchDB:
 * ```
 * echo '["reset"]' | java ... ViewServerJvmKt
 * echo '["add_fun","function(doc){emit(doc._id,1)}"]' | java ...
 * echo '["map_doc",{"_id":"foo"}]' | java ...
 * ```
 */
fun main() {
    // JVM: no-op compile function — map/reduce do nothing by default.
    // Override by registering a real JS engine (Nashorn/Graal) via system property.
    val engine = System.getProperty("viewserver.js.engine", "none")
    val compile: (String) -> CompiledFunction = when (engine) {
        "none" -> { _ -> NoopFunction }
        else -> error("JS engine '$engine' not supported. Use GraalJS or Nashorn.")
    }

    val server = CouchQueryServer(compile)
    val reader = System.`in`.bufferedReader()

    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) continue

        try {
            val ctx = contextOf(Syntax.JSON, line.toSeries())
            val reified = Combinators.reify(ctx)

            @Suppress("UNCHECKED_CAST")
            val jsonList = reified as? List<*> ?: run {
                println(JsonSerializer.serialize(CouchResponse.Error("expected JSON array")))
                continue
            }

            val command = CouchCommand.parse(jsonList) ?: run {
                println(JsonSerializer.serialize(CouchResponse.Error("unknown command")))
                continue
            }

            val response = server.handle(command)
            println(JsonSerializer.serialize(response))
        } catch (e: Exception) {
            println(JsonSerializer.serialize(CouchResponse.Error(e.message ?: "unknown error")))
        }
    }
}

private object NoopFunction : CompiledFunction {
    override fun map(doc: Map<String, Any?>, emit: (key: Any?, value: Any?) -> Unit) {}
    override fun reduce(sources: List<String>, values: List<Any?>, rereduce: Boolean): Any? = null
}

private fun String.toSeries(): borg.trikeshed.lib.Series<Char> {
    val n = length
    return n j { i: Int -> this[i] }
}
