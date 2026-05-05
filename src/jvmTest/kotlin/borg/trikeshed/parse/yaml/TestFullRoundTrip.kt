package borg.trikeshed.parse.yaml

import borg.trikeshed.parse.json.JsonParser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull

class TestFullRoundTrip {
    @Test
    fun debugFullDocument() {
        val jsonText = java.nio.file.Files.readString(Path.of("src/commonTest/resources/big.json"))
        val original = JsonParser.parse(jsonText) as Map<*, *>
        
        val helper = YamlBigJsonParityTest()
        val yaml = helper.renderYaml(original)
        java.nio.file.Files.writeString(Path.of("/tmp/big.yaml"), yaml)
        
        val reparsed = YamlParser.reify(yaml) as Map<*, *>
        
        val stations = reparsed["stations"]
        println("stations type: ${stations?.javaClass}")
        if (stations is List<*>) {
            println("stations count: ${stations.size}")
            if (stations.size > 17) {
                val s17 = stations[17]
                println("s17 type: ${s17?.javaClass}")
                if (s17 is Map<*, *>) {
                    val outfitting = s17["outfitting"]
                    println("outfitting type: ${outfitting?.javaClass}")
                    if (outfitting is Map<*, *>) {
                        val modules = outfitting["modules"]
                        println("modules type: ${modules?.javaClass}")
                        if (modules is List<*>) {
                            println("modules count: ${modules.size}")
                        } else {
                            println("modules value: ${modules?.toString()?.take(200)}")
                        }
                    }
                }
            }
        }
    }
}
