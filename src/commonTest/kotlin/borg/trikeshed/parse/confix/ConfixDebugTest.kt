package borg.trikeshed.parse.confix

import borg.trikeshed.lib.get
import kotlin.test.Test
import borg.trikeshed.lib.*

class ConfixDebugTest {
    @Test
    fun debugCbor() {
        val bytes = byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03)
        val bundle = cborSource(bytes)
        val series = bundle.src
        println("series.size=${series.size}")
        var p = 0
        while (p < series.size) { println("src[$p]=${series[p].code}"); p++ }
        val elems = tokenize(bundle.syntax, series)
        println("elems.size=${elems.size}")
        var i = 0
        while (i < elems.size) {
            val e = elems[i]
            println("elem[$i] open=${e.a.a} close=${e.a.b} tag=${Combinators.tagOf(e, series)}")
            val cs = Combinators.realCommas(e, series)
            var j = 0
            while (j < cs.size) { println("  comma[$j]=${cs[j]}"); j++ }
            i++
        }
    }
}
