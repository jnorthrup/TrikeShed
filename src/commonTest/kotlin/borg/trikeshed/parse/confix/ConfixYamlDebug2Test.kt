package borg.trikeshed.parse.confix

import borg.trikeshed.collections.text.asSeries
import kotlin.test.Test
import borg.trikeshed.lib.get
import borg.trikeshed.lib.*

class ConfixYamlDebug2Test {
    @Test
    fun debugMapSequence() {
        val yaml = """map:
  nested:
    - a
    - b
"""
        val src = yaml.asSeries()
        val ctx = contextOf(Syntax.YAML, src)
        println("--- SOURCE CHARS ---")
        var i = 0
        while (i < src.size) { println("src[${i}]=${src[i]}"); i++ }
        println("--- TOKENIZE ---")
        val elems = tokenize(Syntax.YAML, src)
        var j = 0
        while (j < elems.size) {
            val e = elems[j]
            println("elem[${j}] open=${e.a.a} close=${e.a.b} tag=${Combinators.tagOf(e, src)}")
            val cs = e.b
            var k = 0
            while (k < cs.size) { println("  raw[${k}]=${cs[k]}"); k++ }
            j++
        }

        println("--- RESOLVE PATH map/nested/1 ---")
        val res = Path.resolve(ctx, path("map", "nested", 1))
        println("resolve -> ${res}")
        if (res != null) println("resolved span open=${res.a.a} close=${res.a.b} tag=${Combinators.tagOf(res.a, res.b)}")
    }
}
