package borg.trikeshed.parse.yaml

import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.YamlScan
import borg.trikeshed.parse.confix.asSeries
import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

class TestSellPriceRepro {
    @Test
    fun sellPriceZeroScalar() {
        val yaml = """
items:
  - name: "Tea"
    demand: 0
    supply: 1
    sellPrice: 0
  - name: "Silver"
    demand: 0
    sellPrice: 0
""".trimIndent()
        val src = yaml.asSeries()
        val elems = YamlScan.scan(src)
        println("Total elements: ${elems.size}")
        for (i in 0 until elems.size) {
            val elem = elems[i]
            val tag = Combinators.tagOf(elem, src)
            val text = Combinators.textOf(elem, src).replace("\n", "\\n").take(40)
            println("  [$i] tag=$tag open=${elem.a.a} close=${elem.a.b} text=\"$text\"")
        }
        
        val result = YamlParser.reify(yaml) as Map<*, *>
        val items = result["items"] as List<*>
        val tea = items[0] as Map<*, *>
        println("tea: $tea")
        assertEquals(0, tea["sellPrice"], "sellPrice should be 0 not null")
    }
}
