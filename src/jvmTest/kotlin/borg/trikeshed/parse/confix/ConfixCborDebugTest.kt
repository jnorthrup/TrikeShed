package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import borg.trikeshed.parse.confix.Path
import borg.trikeshed.parse.confix.Reify
import borg.trikeshed.parse.confix.cborSource
import borg.trikeshed.parse.confix.path
import borg.trikeshed.parse.confix.tokenize
import kotlin.test.Test

class ConfixCborDebugTest {
    @Test
    fun debugCbor() {
        val bytes = byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03)
        val src = cborSource(bytes)
        val elems = tokenize(src.syntax, src.src)
        println("elems.size=${elems.size}")
        var i = 0
        while (i < elems.size) {
            val e = elems[i]
            val open = e.a.a; val close = e.a.b
            val cs = e.b
            var j = 0
            val comms = StringBuilder()
            while (j < cs.size) {
                if (j > 0) comms.append(",")
                comms.append(cs[j])
                j++
            }
            println("elem[$i] open=$open close=$close commas=[$comms]")
            i++
        }
        val root = elems[0] j src.src
        val resolved = Path.resolve(root, path(1))
        println("resolved: $resolved")
        if (resolved != null) {
            println("resolved open=${resolved.a.a} close=${resolved.a.b}")
            val v = Reify.reify(resolved)
            println("reified value = $v")
        }
    }
}
