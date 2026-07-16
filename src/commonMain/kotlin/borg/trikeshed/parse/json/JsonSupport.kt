@file:Suppress("UNCHECKED_CAST")
package borg.trikeshed.parse.json

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.parse.confix.*

/**
 * Static-link friendly JSON facade.
 * Delegates to the confix JSON stack.
 */
object JsonSupport {
    fun parse(text: String): Any? = JsonParser.reify(CharSeries(text))

    /** Confix-compatible JSON rendering without a second serialization runtime. */
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (char in value) {
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else append(char)
                }
            }
            append('"')
        }
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${stringify(key.toString())}:${stringify(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        else -> stringify(value.toString())
    }

    fun query(text: String, vararg pathSteps: Any?): Any? {
        val map = JsonParser.reify(CharSeries(text))
        return resolvePath(map, pathSteps.toList())
    }

    private fun resolvePath(node: Any?, steps: List<*>): Any? {
        var current: Any? = node
        for (step in steps) {
            current = when {
                current == null -> null
                current is Map<*, *> -> when (step) {
                    is String -> current[step]
                    is Int -> current.entries.drop(step).firstOrNull()?.value
                    else -> null
                }
                current is List<*> -> when (step) {
                    is Int -> current.getOrNull(step)
                    is String -> null
                    else -> null
                }
                else -> null
            }
        }
        return current
    }
}
