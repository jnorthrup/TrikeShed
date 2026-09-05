package borg.trikeshed.lcnc

import borg.trikeshed.ccek.ProjectionKind

/** Construction settings are immutable for the lifetime of an incarnation. */
data class CcekConstruction(
    val title: String,
    val record: Boolean,
    val maxConcurrency: Int,
    val projections: Set<ProjectionKind>,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "handle" to title, "record" to record, "maxConcurrency" to maxConcurrency,
        "projections" to projections.map { it.name }.sorted(),
    )

    data class Resolved(val configuration: CcekConstruction, val arguments: List<Map<String, Any?>>)

    companion object {
        fun resolve(params: Map<String, String>, inputs: Map<String, Any?>): Resolved {
            val envelope = if (inputs.containsKey("args")) inputs["args"] else inputs["args?"]
            require(envelope == null && !inputs.containsKey("args") && !inputs.containsKey("args?") || envelope is Map<*, *>) {
                "ccek.incarnate args must be an object"
            }
            val args = envelope as? Map<*, *> ?: emptyMap<String, Any?>()
            val names = setOf("title", "record", "maxConcurrency", "projections")
            require(args.keys.all { it is String && it in names }) { "ccek.incarnate unknown construction argument" }
            val rows = ArrayList<Map<String, Any?>>()
            fun resolve(name: String, type: String, fallback: Any, decode: (Any?) -> Any): Any {
                val port = when { inputs.containsKey(name) -> name; inputs.containsKey("$name?") -> "$name?"; else -> null }
                val source = when { port != null -> "input"; args.containsKey(name) -> "args"; params.containsKey(name) -> "parameter"; else -> "default" }
                val raw = when (source) { "input" -> inputs[port]; "args" -> args[name]; "parameter" -> params[name]; else -> fallback }
                val value = decode(raw)
                val overridden = when {
                    port != null && args.containsKey(name) -> mapOf("source" to "args", "value" to args[name])
                    source in listOf("input", "args") && params.containsKey(name) -> mapOf("source" to "parameter", "value" to params[name])
                    else -> null
                }
                rows.add(linkedMapOf("name" to name, "type" to type, "source" to source,
                    "value" to value, "overridden" to overridden, "status" to "validated"))
                return value
            }
            val title = resolve("title", "text", "lcnc-node") {
                require(it is String && it.isNotBlank()) { "ccek.incarnate title must be nonempty text" }; it
            } as String
            val record = resolve("record", "boolean", true) {
                when (it) { true, "true" -> true; false, "false" -> false; else -> error("ccek.incarnate record must be boolean") }
            } as Boolean
            val concurrency = resolve("maxConcurrency", "integer", 8) {
                val number = when (it) { is Number -> it.toDouble(); is String -> it.toDoubleOrNull(); else -> null }
                require(number != null && number.isFinite() && number >= 1 && number <= 256 && number % 1.0 == 0.0) {
                    "ccek.incarnate maxConcurrency must be an integer in 1..256"
                }; number.toInt()
            } as Int
            val projections = resolve("projections", "projection[]", ProjectionKind.ALL.map { it.name }) {
                val parts = when (it) {
                    is String -> if (it.isBlank()) ProjectionKind.ALL.map { p -> p.name } else it.split(',').map(String::trim)
                    is List<*> -> it
                    else -> error("ccek.incarnate projections must be a list or comma-separated names")
                }
                require(parts.isNotEmpty()) { "ccek.incarnate projections cannot be empty" }
                parts.map { part ->
                    require(part is String) { "ccek.incarnate projection must be text" }
                    requireNotNull(ProjectionKind.entries.find { p -> p.name.equals(part, ignoreCase = true) }) {
                        "ccek.incarnate unknown projection: $part"
                    }.name
                }.distinct().sorted()
            } as List<*>
            return Resolved(CcekConstruction(title, record, concurrency,
                projections.map { ProjectionKind.valueOf(it as String) }.toSet()), rows)
        }
    }
}
