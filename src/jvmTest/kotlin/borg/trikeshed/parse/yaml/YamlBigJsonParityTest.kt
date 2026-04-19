package borg.trikeshed.parse.yaml

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YamlBigJsonParityTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun bigJsonYamlRoundTrip_matchesRandomLeafSamples() {
        val jsonText = java.nio.file.Files.readString(Path.of("src/commonTest/resources/big.json"))
        val original = mapper.readValue(jsonText, Any::class.java)
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

    private fun renderYaml(value: Any?, indent: Int = 0): String {
        val prefix = " ".repeat(indent)
        return when (value) {
            null -> "null"
            is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString("\n") { (key, child) ->
                val keyText = key.toString()
                val rendered = renderYaml(child, indent + 2)
                if (isInline(child)) "$prefix$keyText: $rendered" else "$prefix$keyText:\n$rendered"
            }
            is List<*> -> if (value.isEmpty()) "[]" else value.joinToString("\n") { child ->
                renderListItem(child, indent)
            }
            is String -> quoteYaml(value)
            is Boolean, is Int, is Long, is Double, is Float -> value.toString()
            else -> quoteYaml(value.toString())
        }
    }

    private fun renderListItem(value: Any?, indent: Int): String {
        val prefix = " ".repeat(indent)
        return when {
            isInline(value) -> "$prefix- ${renderYaml(value, indent + 2)}"
            value is Map<*, *> -> {
                val entries = value.entries.toList()
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
            value is List<*> -> "$prefix- ${quoteYaml(renderYaml(value, indent + 2))}"
            else -> "$prefix- ${renderYaml(value, indent + 2)}"
        }
    }

    private fun quoteYaml(value: String): String =
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

    private fun isInline(value: Any?): Boolean =
        value == null ||
            value is String ||
            value is Number ||
            value is Boolean ||
            (value is Map<*, *> && value.isEmpty()) ||
            (value is List<*> && value.isEmpty())

    private fun collectLeafPaths(value: Any?, prefix: List<Any>, output: MutableList<List<Any>>) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) -> collectLeafPaths(child, prefix + key.toString(), output) }
            is List<*> -> value.forEachIndexed { index, child -> collectLeafPaths(child, prefix + index, output) }
            else -> output += prefix
        }
    }

    private fun resolvePath(root: Any?, path: List<Any>): Any? {
        var current = root
        for (segment in path) {
            current = when {
                segment is String && current is Map<*, *> -> current[segment]
                segment is Int && current is List<*> -> current[segment]
                else -> error("Path $path is invalid at segment $segment for value $current")
            }
        }
        return current
    }

    private fun assertEquivalent(path: List<Any>, left: Any?, right: Any?) {
        when {
            left is Number && right is Number -> {
                if (left is Double || left is Float || right is Double || right is Float) {
                    assertEquals(left.toDouble(), right.toDouble(), 1e-9, "Mismatch at $path")
                } else {
                    assertEquals(left.toLong(), right.toLong(), "Mismatch at $path")
                }
            }
            else -> assertEquals(left, right, "Mismatch at $path")
        }
    }
}
