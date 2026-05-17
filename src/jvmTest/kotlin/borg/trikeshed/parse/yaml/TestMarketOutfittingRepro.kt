package borg.trikeshed.parse.yaml

import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.YamlScan
import borg.trikeshed.lib.*
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TestMarketOutfittingRepro {
    @Test
    fun stationWithMarketThenOutfitting() {
        val yaml = """
stations:
  - name: "T5K-9TB"
    id: 3702556928
    type: "Drake-Class Carrier"
    landingPads:
      large: 8
      medium: 4
      small: 4
    market:
      commodities: []
      prohibitedCommodities:
        - "Narcotics"
        - "Beer"
      updateTime: "2022-11-19"
    outfitting:
      modules:
        - name: "Heat Sink Launcher"
          symbol: "Hpt_HeatSinkLauncher_Turret_Tiny"
          moduleId: 128049519
          class: 0
          rating: "I"
          category: "utility"
""".trimIndent()
        val result = YamlParser.reify(yaml) as Map<*, *>
        val stations = result["stations"] as List<*>
        val s0 = stations[0] as Map<*, *>
        val keys = s0.keys.toList()
        assertEquals(listOf("name", "id", "type", "landingPads", "market", "outfitting"), keys)
        val outfitting = s0["outfitting"] as? Map<*, *>
        assertNotNull(outfitting, "outfitting should exist")
        val modules = outfitting["modules"] as? List<*>
        assertNotNull(modules, "modules should exist")
        assertEquals(1, modules.size)
        val mod0 = modules[0] as Map<*, *>
        assertEquals("Heat Sink Launcher", mod0["name"])
    }

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
        // Dump elements
        val src = yaml.toSeries()
        val elems = YamlScan.scan(src)
        val n = elems.a
        println("Total elements: $n")
        for (i in 0 until n) {
            val elem = elems[i]
            val tag = Combinators.tagOf(elem, src)
            val text = Combinators.textOf(elem, src).replace("\n", "\\n").take(40)
            println("  [$i] tag=$tag open=${elem.a.a} close=${elem.a.b} text=\"$text\"")
        }

        val result = YamlParser.reify(yaml) as Map<*, *>
        val items = result["items"] as List<*>
        val tea = items[0] as Map<*, *>
        println("tea keys: ${tea.keys}")
        assertEquals(0, tea["sellPrice"], "sellPrice should be 0 not null")
    }
}
