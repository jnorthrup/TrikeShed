package borg.trikeshed.parse.confix

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfixYamlDebugStringTest {
    @Test
    fun debugQuotedString() {
        val yaml = """list:
  - 'one'
  - "two\nline"
"""
        val src = yaml.asSeries()
        val ctx = contextOf(Syntax.YAML, src)
        println("--- SOURCE CHARS ---")
        var i = 0
        while (i < src.size) { println("src[$i]=${src[i]}"); i++ }
        println("--- TOKENIZE ---")
        val elems = tokenize(Syntax.YAML, src)
        var j = 0
        while (j < elems.size) {
            val e = elems[j]
            println("elem[$j] open=${e.a.a} close=${e.a.b} tag=${Combinators.tagOf(e, src)}")
            val cs = e.b
            var k = 0
            while (k < cs.size) { println("  raw[$k]=${cs[k]}"); k++ }
            j++
        }

        println("--- RESOLVE PATH list/1 ---")
        val res = Path.resolve(ctx, path("list", 1))
        println("resolve -> $res")
        if (res != null) {
            println("resolved span open=${res.a.a} close=${res.a.b} tag=${Combinators.tagOf(res.a, res.b)}")
            val v = Combinators.reify(res)
            println("reified -> $v")
            assertEquals("two\nline", v)
        }
    }
}
