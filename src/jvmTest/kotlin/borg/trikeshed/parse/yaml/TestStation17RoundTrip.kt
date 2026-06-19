package borg.trikeshed.parse.yaml

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull

class TestStation17RoundTrip {
    @Test
    fun debugStation17() {
        val path = Path.of("/tmp/station17.yaml")
        if (!java.nio.file.Files.exists(path)) {
            val jsonText = java.nio.file.Files.readString(Path.of("src/commonTest/resources/big.json"))
            val original = borg.trikeshed.parse.json.JsonSupport.parse(jsonText) as Map<*, *>
            val stations = original["stations"] as List<*>
            val s17 = stations[17]
            val helper = YamlBigJsonParityTest()
            val yamlText = helper.renderYaml(mapOf("stations" to listOf(s17)))
            java.nio.file.Files.createDirectories(path.parent)
            java.nio.file.Files.writeString(path, yamlText)
        }
        val yaml = java.nio.file.Files.readString(path)
        val reparsed = YamlParser.reify(yaml) as Map<*, *>
        val stations = reparsed["stations"] as List<*>
        println("stations count: ${stations.size}")
        val s0 = stations[0] as Map<*, *>
        println("s0 keys: ${s0.keys}")
        val outfitting = s0["outfitting"]
        println("outfitting type: ${outfitting?.javaClass}")
        if (outfitting is Map<*, *>) {
            val modules = outfitting["modules"]
            println("modules type: ${modules?.javaClass}")
            if (modules is List<*>) {
                println("modules count: ${modules.size}")
            } else {
                println("modules value: ${modules?.toString()?.take(200)}")
            }
        } else {
            println("outfitting value: ${outfitting?.toString()?.take(200)}")
        }
        assertNotNull(outfitting, "outfitting should not be null")
    }
}
