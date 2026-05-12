package borg.trikeshed.openapi

import borg.trikeshed.parse.yaml.YamlParser
import kotlin.test.Test

class YamlDebugTest {
    @Test
    fun debugKrakenYaml() {
        val text = java.io.File("../krak/rest-api/openapi/kraken.openapi.yaml").readText()
        @Suppress("UNCHECKED_CAST")
        val result = YamlParser.reify(text) as Map<CharSequence, Any?>
        println("=== KRANKEN YAML ===")
        println("Top-level keys: ${result.keys}")
        println("paths key present: ${result.containsKey("paths")}")
        val paths = result["paths"]
        println("paths value type: ${paths?.javaClass?.simpleName}")
        println("paths value: $paths")

        result.forEach { k, v ->
            when (v) {
                null -> println("  $k = null")
                is Map<*, *> -> {
                    println("  $k = Map with ${(v as Map<*,*>).size} entries")
                    if (k == "paths") {
                        v.forEach { pk, pv ->
                            println("    $pk -> ${if (pv is Map<*,*>) "Map(${pv.size})" else pv}")
                        }
                    }
                }
                else -> println("  $k = ${v::class.simpleName}: $v")
            }
        }
    }

    @Test
    fun debugCmcYaml() {
        val text = java.io.File("../cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml").readText()
        @Suppress("UNCHECKED_CAST")
        val result = YamlParser.reify(text) as Map<CharSequence, Any?>
        println("=== CMC YAML ===")
        println("Top-level keys: ${result.keys}")
        println("paths key present: ${result.containsKey("paths")}")
        println("paths value type: ${result["paths"]?.javaClass?.simpleName}")
    }

    @Test
    fun debugTokenizerOutput() {
        // Test the specific problematic section: items: {}
        val problematic = """
openapi: 3.1.0
info:
  title: Test
components:
  schemas:
    GenericArray:
      type: array
      items: {}
paths:
  /test:
    get:
      operationId: testOp
      responses:
        "200":
          description: OK
""".trimIndent()

        // Now test with items: {} on its own line (no trailing newline)
        val noTrailingNewline = "openapi: 3.1.0\ninfo:\n  title: Test\ncomponents:\n  schemas:\n    GenericArray:\n      type: array\n      items: {}\npaths:\n  /test:\n    get:\n      operationId: testOp"

        @Suppress("UNCHECKED_CAST")
        val result = YamlParser.reify(problematic) as Map<CharSequence, Any?>
        println("=== PROBLEMatic YAML ===")
        println("Keys: ${result.keys}")
        println("paths present: ${result.containsKey("paths")}")

        @Suppress("UNCHECKED_CAST")
        val result2 = YamlParser.reify(noTrailingNewline) as Map<CharSequence, Any?>
        println("=== NO-TRAILING-NL YAML ===")
        println("Keys: ${result2.keys}")
        println("paths present: ${result2.containsKey("paths")}")
    }
}
