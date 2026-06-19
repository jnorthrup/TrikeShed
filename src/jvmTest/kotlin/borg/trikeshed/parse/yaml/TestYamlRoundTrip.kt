package borg.trikeshed.parse.yaml

import borg.trikeshed.parse.json.JsonSupport
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull

class TestYamlRoundTrip {
    @Test
    fun debugDeepListNesting() {
        val jsonText = java.nio.file.Files.readString(Path.of("src/commonTest/resources/big.json"))
        val original = JsonSupport.parse(jsonText) as Map<*, *>
        val stations = original["stations"] as List<*>
        val s17 = stations[17] as Map<*, *>
        val outfitting = s17["outfitting"] as Map<*, *>
        val modules = outfitting["modules"] as List<*>
        println("Original modules count: ${modules.size}")
        
        val helper = YamlBigJsonParityTest()
        val yaml = helper.renderYaml(outfitting)
        java.nio.file.Files.writeString(Path.of("/tmp/outfitting.yaml"), yaml)
        
        // Test just the outfitting map
        val reparsed = YamlParser.reify(yaml)
        println("Reparsed type: ${reparsed?.javaClass}")
        if (reparsed is Map<*, *>) {
            val rModules = reparsed["modules"]
            println("Reparsed modules type: ${rModules?.javaClass}")
            if (rModules is List<*>) {
                println("Reparsed modules count: ${rModules.size}")
                if (rModules.size > 36) {
                    println("modules[36]: ${rModules[36]}")
                }
            } else {
                println("Reparsed modules value: $rModules")
            }
        }
        assertNotNull(reparsed, "Reparsed should not be null")
    }
}
