package borg.trikeshed.parse.json

import kotlin.time.TimeSource

/** Preflight before serialization: bounded traversal, including strings and container keys. */
data class ValueBudget(
    val maxNodes: Int = 8192,
    val maxChars: Int = 131072,
    val maxDepth: Int = 24,
    val maxMillis: Long = 50,
) {
    fun violation(value: Any?): String? {
        val started = TimeSource.Monotonic.markNow()
        var nodes = 0
        var chars = 0L
        fun visit(v: Any?, depth: Int): String? {
            if (++nodes > maxNodes) return "work_limit"
            if (depth > maxDepth) return "depth_limit"
            if (started.elapsedNow().inWholeMilliseconds > maxMillis) return "time_limit"
            when (v) {
                null, is Boolean -> chars += 5
                is Number -> chars += 32
                is String -> chars += v.length.toLong()
                is Map<*, *> -> for ((key, child) in v) {
                    visit(key, depth + 1)?.let { return it }
                    visit(child, depth + 1)?.let { return it }
                }
                is List<*> -> for (child in v) visit(child, depth + 1)?.let { return it }
                is Array<*> -> for (child in v) visit(child, depth + 1)?.let { return it }
                else -> return "unsupported_value"
            }
            return if (chars > maxChars) "payload_limit" else null
        }
        return visit(value, 0)
    }
}
