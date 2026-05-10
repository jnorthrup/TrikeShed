@file:Suppress("unused")
package borg.trikeshed.parse.yaml

import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import kotlin.test.Test
import java.io.File

class DebugYamlParser {
    @Test
    fun debugElems() {
        val yaml = """
           |openapi: 3.1.0
           |info:
           |  title: Example API
           |  version: 1.0.0
           |tags:
           |  - pets
           |  - orders
           |paths:
           |  /pets:
           |    get:
           |      operationId: listPets
           |""".trimMargin( )

        val src = yaml.asSeries()
        val elems = YamlScan.scan(src)
        val sb = StringBuilder()
        sb.appendLine("yaml length=${yaml.length}")
        sb.appendLine("elems.size=${elems.size}")
        for (i in 0 until elems.size) {
            val e = elems[i]
            val tag = Combinators.tagOf(e, src)
            val text = Combinators.textOf(e, src)
            val commas = e.b
            val commaVals = (0 until commas.size).map { commas[it] }
            sb.appendLine("  [$i] tag=$tag open=${e.a.a} close=${e.a.b} text=[${text.take(30).replace("\n","\\n")}] commas=$commaVals")
        }
        File("/tmp/yaml_debug.txt").writeText(sb.toString())

        // Now check what extractChildIndices returns for root (element 0)
        val doc = YamlParser.parse(yaml)
        val root = doc.root
        sb.appendLine("\nroot type: ${root::class.simpleName}")
        if (root is YamlMappingNode) {
            for ((key, node) in root.entries) {
                sb.appendLine("  key=$key node=${node::class.simpleName}")
            }
        }
        File("/tmp/yaml_debug.txt").writeText(sb.toString())
    }
}
