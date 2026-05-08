package borg.trikeshed.viewserver

/**
 * CouchDB 1.7.2 query server state machine.
 *
 * Manages the lifecycle of view functions across reset/add_fun/map_doc/reduce/rereduce.
 * This is pure logic — IO (stdin/stdout) is handled by platform bindings.
 *
 * The `compile` function is platform-specific (JS `eval`, JVM Nashorn/Graal, etc.).
 */
class CouchQueryServer(
    private val compile: (String) -> CompiledFunction,
) {
    private val functions = mutableListOf<CompiledFunction>()

    fun handle(command: CouchCommand): CouchResponse = when (command) {
        is CouchCommand.Reset -> {
            functions.clear()
            CouchResponse.True
        }
        is CouchCommand.AddFun -> {
            try {
                val fn = compile(command.source)
                functions.add(fn)
                CouchResponse.True
            } catch (e: Exception) {
                CouchResponse.Error("compile_error: ${e.message}")
            }
        }
        is CouchCommand.MapDoc -> {
            val allResults = mutableListOf<List<List<Any?>>>()
            for (fn in functions) {
                try {
                    val emitted = mutableListOf<Pair<Any?, Any?>>()
                    fn.map(command.doc) { key, value -> emitted.add(key to value) }
                    allResults.add(emitted.map { (k, v) -> listOf(k, v) })
                } catch (e: Exception) {
                    allResults.add(emptyList())
                }
            }
            CouchResponse.MapResults(allResults)
        }
        is CouchCommand.Reduce -> {
            try {
                val values = command.values
                if (functions.isEmpty()) return CouchResponse.ReduceResult(null)
                val fn = functions.first()
                val result = fn.reduce(command.sources, values, false)
                CouchResponse.ReduceResult(result)
            } catch (e: Exception) {
                CouchResponse.Error("reduce_error: ${e.message}")
            }
        }
        is CouchCommand.Rereduce -> {
            try {
                val values = command.values
                if (functions.isEmpty()) return CouchResponse.ReduceResult(null)
                val fn = functions.first()
                val result = fn.reduce(command.sources, values, true)
                CouchResponse.ReduceResult(result)
            } catch (e: Exception) {
                CouchResponse.Error("rereduce_error: ${e.message}")
            }
        }
    }
}

/**
 * A compiled view function — platform-specific implementation.
 * Platform bindings provide [compile] via:
 *   - JS: `kotlin.js.eval(source)` wrapping
 *   - JVM: Nashorn / GraalVM JS engine
 */
interface CompiledFunction {
    /** Map a single document, calling [emit] for each key-value pair. */
    fun map(doc: Map<String, Any?>, emit: (key: Any?, value: Any?) -> Unit)

    /**
     * Reduce (or rereduce) values.
     * @param sources  function source strings
     * @param values   the values to reduce
     * @param rereduce  true if this is a rereduce call
     */
    fun reduce(sources: List<String>, values: List<Any?>, rereduce: Boolean): Any?
}

// ── JSON response serializer ─────────────────────────────────────────────

/**
 * Minimal JSON serializer for CouchDB view server responses.
 *
 * Handles: booleans, strings, numbers, arrays, and maps.
 * No external dependencies — suitable for commonMain.
 */
object JsonSerializer {
    fun serialize(response: CouchResponse): String = when (response) {
        is CouchResponse.True -> "true"
        is CouchResponse.Error -> serializeString(response.message)
        is CouchResponse.ReduceResult -> "[true,[${serializeValue(response.value)}]]"
        is CouchResponse.MapResults -> {
            val parts = response.perFunction.joinToString(",") { fnResults ->
                "[${fnResults.joinToString(",") { kv ->
                    "[${kv.joinToString(",") { serializeValue(it) }}]"
                }}]"
            }
            "[$parts]"
        }
    }

    private fun serializeValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        is String -> serializeString(value)
        is List<*> -> "[${value.joinToString(",") { serializeValue(it) }}]"
        is Map<*, *> -> {
            val entries = value.entries.joinToString(",") { (k, v) ->
                "${serializeValue(k)}:${serializeValue(v)}"
            }
            "{$entries}"
        }
        else -> serializeString(value.toString())
    }

    private fun serializeString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u${ch.code.toString(16).padStart(4, '0')}")
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
