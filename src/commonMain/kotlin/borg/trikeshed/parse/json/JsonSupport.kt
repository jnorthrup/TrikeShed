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
