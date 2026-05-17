@file:Suppress("unused")
package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.lib.toSeries
import kotlin.test.Test

class DebugKrakenShape {
    @Test
    fun debugSequenceAtRoot() {
        val yaml = """
            |openapi: 1.0
            |servers:
            |- url: https://a.com
            |  description: Server A
            |- url: https://b.com
            |tags:
            |- name: Market Data
            |  description: Public data
            |- name: Trading
            |paths:
            |  /test:
            |    get:
            |      operationId: testOp
            |""".trimMargin()

        val src = yaml.toSeries()
        val elems = YamlScan.scan(src)
        val sb = StringBuilder()
        sb.appendLine("yaml length=${yaml.length}, elems=${elems.size}")
        for (i in 0 until elems.size) {
            val e = elems[i]
            val tag = Combinators.tagOf(e, src)
            val text = Combinators.textOf(e, src).take(40).replace("\n", "\\n")
            sb.appendLine("  [$i] tag=$tag open=${e.a.a} close=${e.a.b} text=[$text]")
        }

        // Try to resolve "paths" via contextOf
        val ctx = contextOf(Syntax.YAML, src)
        val vPaths = Path.resolve(ctx, path("paths"))
        sb.appendLine("\npaths resolved: ${vPaths != null}")
        if (vPaths != null) {
            val tag = Combinators.tagOf(vPaths.a, src)
            val text = Combinators.textOf(vPaths.a, src).take(40).replace("\n", "\\n")
            sb.appendLine("  tag=$tag text=[$text]")
        }

        // Try each top-level key
        for (key in listOf("openapi", "servers", "tags", "paths")) {
            val resolved = Path.resolve(ctx, path(key))
            sb.appendLine("$key resolved: ${resolved != null}")
        }

        java.io.File("/tmp/kraken_debug.txt").writeText(sb.toString())
    }
}
