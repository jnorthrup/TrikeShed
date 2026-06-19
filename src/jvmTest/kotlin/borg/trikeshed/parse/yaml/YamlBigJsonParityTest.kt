package borg.trikeshed.parse.yaml

import borg.trikeshed.parse.json.JsonSupport
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YamlBigJsonParityTest {

    @Test
    fun bigJsonYamlRoundTrip_matchesRandomLeafSamples() {
        val jsonText = java.nio.file.Files.readString(Path.of("src/commonTest/resources/big.json"))
        val original = JsonSupport.parse(jsonText)
        val yaml = renderYaml(original)
        val reparsed = YamlParser.reify(yaml)

        val leafPaths = mutableListOf<List<Any>>()
        collectLeafPaths(original, emptyList(), leafPaths)
        assertTrue(leafPaths.isNotEmpty(), "Expected at least one leaf path in big.json")

        val rng = Random(553280777)
        val sampleSize = minOf(64, leafPaths.size)
        val sampled = leafPaths.shuffled(rng).take(sampleSize)

        sampled.forEach { path ->
            val left = resolvePath(original, path)
            val right = resolvePath(reparsed, path)
            assertEquivalent(path, left, right)
        }
    }

    fun normalize(value: Any?): Any? = when (value) {
        is Array<*> -> value.toList()
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is DoubleArray -> value.toList()
        is FloatArray -> value.toList()
        is ShortArray -> value.toList()
        is ByteArray -> value.toList()
        is BooleanArray -> value.toList()
        is CharArray -> value.toList()
        else -> value
    }

   fun renderYaml(value: Any?, indent: Int = 0): String {
        val normValue = normalize(value)
        val prefix = " ".repeat(indent)
        return when (normValue) {
            null -> "null"
            is Map<*, *> -> if (normValue.isEmpty()) "{}" else normValue.entries.joinToString("\n") { (key, child) ->
                val keyText = key.toString()
                val rendered = renderYaml(child, indent + 2)
                if (isInline(child)) "$prefix$keyText: $rendered" else "$prefix$keyText:\n$rendered"
            }
            is List<*> -> if (normValue.isEmpty()) "[]" else normValue.joinToString("\n") { child ->
                renderListItem(child, indent)
            }
            is String -> quoteYaml(normValue)
            is Boolean, is Int, is Long, is Double, is Float -> normValue.toString()
            else -> quoteYaml(normValue.toString())
        }
    }

   fun renderListItem(value: Any?, indent: Int): String {
        val normValue = normalize(value)
        val prefix = " ".repeat(indent)
        return when {
            isInline(normValue) -> "$prefix- ${renderYaml(normValue, indent + 2)}"
            normValue is Map<*, *> -> {
                val entries = normValue.entries.toList()
                val first = entries.first()
                val firstKey = first.key.toString()
                val firstValue = first.value
                val head =
                    if (isInline(firstValue)) {
                        "$prefix- $firstKey: ${renderYaml(firstValue, indent + 4)}"
                    } else {
                        "$prefix- $firstKey:\n${renderYaml(firstValue, indent + 4)}"
                    }
                val tail = entries.drop(1).joinToString("\n") { (key, child) ->
                    val keyPrefix = " ".repeat(indent + 2)
                    if (isInline(child)) {
                        "$keyPrefix${key.toString()}: ${renderYaml(child, indent + 4)}"
                    } else {
                        "$keyPrefix${key.toString()}:\n${renderYaml(child, indent + 4)}"
                    }
                }
                listOf(head, tail).filter { it.isNotEmpty() }.joinToString("\n")
            }
            normValue is List<*> -> "$prefix- ${quoteYaml(renderYaml(normValue, indent + 2))}"
            else -> "$prefix- ${renderYaml(normValue, indent + 2)}"
        }
    }

   fun quoteYaml(value: String): String =
        buildString(value.length + 2) {
            append('"')
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }

   fun isInline(value: Any?): Boolean {
        val normValue = normalize(value)
        return normValue == null ||
            normValue is String ||
            normValue is Number ||
            normValue is Boolean ||
            (normValue is Map<*, *> && normValue.isEmpty()) ||
            (normValue is List<*> && normValue.isEmpty())
   }

   fun collectLeafPaths(value: Any?, prefix: List<Any>, output: MutableList<List<Any>>) {
        val normValue = normalize(value)
        when (normValue) {
            is Map<*, *> -> normValue.forEach { (key, child) -> collectLeafPaths(child, prefix + key.toString(), output) }
            is List<*> -> normValue.forEachIndexed { index, child -> collectLeafPaths(child, prefix + index, output) }
            else -> output += prefix
        }
    }

   fun resolvePath(root: Any?, path: List<Any>): Any? {
        var current = root
        for (segment in path) {
            val normCurrent = normalize(current)
            current = when {
                segment is String && normCurrent is Map<*, *> -> normCurrent[segment]
                segment is Int && normCurrent is List<*> -> normCurrent[segment]
                else -> error("Path $path is invalid at segment $segment for value $current")
            }
        }
        return current
    }

   fun assertEquivalent(path: List<Any>, left: Any?, right: Any?) {
        when {
            left is Number && right is Number -> {
                if (left is Double || left is Float || right is Double || right is Float) {
                    assertEquals(left.toDouble(), right.toDouble(), 1e-4, "Mismatch at $path")
                } else {
                    assertEquals(left.toLong(), right.toLong(), "Mismatch at $path")
                }
            }
            else -> assertEquals(left, right, "Mismatch at $path")
        }
    }
}
