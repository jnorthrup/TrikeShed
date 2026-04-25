package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.parse.confix.Reify
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.asSeries
import borg.trikeshed.parse.confix.tokenize
import kotlin.test.Test

class ConfixJsonDebugTest {
    @Test
    fun debugJson() {
        val json = """{"s":"line1\\nline2","u":"\\u0041"}"""
        val src = json.asSeries()
        println("src.size=${src.size}")
        var i = 0
        while (i < src.size) { println("src[$i]=${src[i]}"); i++ }
        val elems = tokenize(Syntax.JSON, src)
        println("elems.size=${elems.size}")
        var j = 0
        while (j < elems.size) {
            val e = elems[j]
            println("elem[$j] open=${e.a.a} close=${e.a.b} tag=${Reify.tagOf(e, src)}")
            val cs = Reify.realCommas(e)
            var k = 0
            while (k < cs.size) {
                println("  comma[$k]=${cs[k]}")
                k++
            }
            j++
        }
    }
}
