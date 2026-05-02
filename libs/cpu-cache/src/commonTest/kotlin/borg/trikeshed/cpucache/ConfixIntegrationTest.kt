package borg.trikeshed.cpucache

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Confix integration tests for CpuCacheTopology.
 * 
 * Verifies that output is valid JSON/YAML parseable by Confix tokenizers.
 */
class CpuCacheConfixTest {

    @Test
    fun testToJsonProducesValidJson() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = 32768,
            l2Bytes = 262144,
            l3Bytes = null,
            cacheLineBytes = 64,
            coreCount = 8
        )
        
        val json = CpuCacheConfix.toJson(topology)
        
        // Verify JSON contains expected fields
        assertTrue(json.contains("\"l1DataBytes\": 32768"), "Missing l1DataBytes")
        assertTrue(json.contains("\"l1InstructionBytes\": 32768"), "Missing l1InstructionBytes")
        assertTrue(json.contains("\"l2Bytes\": 262144"), "Missing l2Bytes")
        assertTrue(json.contains("\"l3Bytes\": null"), "Missing l3Bytes")
        assertTrue(json.contains("\"cacheLineBytes\": 64"), "Missing cacheLineBytes")
        assertTrue(json.contains("\"coreCount\": 8"), "Missing coreCount")
        
        // Verify valid JSON structure
        assertTrue(json.startsWith("{\n"), "JSON should start with {\\n")
        assertTrue(json.endsWith("\n}"), "JSON should end with \\n}")
        assertTrue(json.contains(",\n"), "JSON should contain commas between fields")
    }
    
    @Test
    fun testToYamlProducesValidYaml() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = 32768,
            l2Bytes = 262144,
            l3Bytes = null,
            cacheLineBytes = 64,
            coreCount = 8
        )
        
        val yaml = CpuCacheConfix.toYaml(topology)
        
        // Verify YAML contains expected fields (block style)
        assertTrue(yaml.contains("l1DataBytes: 32768"), "Missing l1DataBytes in YAML")
        assertTrue(yaml.contains("l1InstructionBytes: 32768"), "Missing l1InstructionBytes in YAML")
        assertTrue(yaml.contains("l2Bytes: 262144"), "Missing l2Bytes in YAML")
        assertTrue(yaml.contains("l3Bytes: null"), "Missing l3Bytes in YAML")
        assertTrue(yaml.contains("cacheLineBytes: 64"), "Missing cacheLineBytes in YAML")
        assertTrue(yaml.contains("coreCount: 8"), "Missing coreCount in YAML")
        
        // Verify no flow-style markers (should be block YAML)
        assertTrue(!yaml.contains("{"), "YAML should not use flow style")
        assertTrue(!yaml.contains("}"), "YAML should not use flow style")
    }
    
    @Test
    fun testConfixJsonExtensionProperty() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = 32768,
            l2Bytes = 262144,
            l3Bytes = null,
            cacheLineBytes = 64,
            coreCount = 8
        )
        
        val json = topology.confixJson
        
        assertTrue(json.contains("\"l1DataBytes\": 32768"))
        assertTrue(json.startsWith("{\n"))
        assertTrue(json.endsWith("\n}"))
    }
    
    @Test
    fun testConfixYamlExtensionProperty() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = 32768,
            l2Bytes = 262144,
            l3Bytes = null,
            cacheLineBytes = 64,
            coreCount = 8
        )
        
        val yaml = topology.confixYaml
        
        assertTrue(yaml.contains("l1DataBytes: 32768"))
        assertTrue(!yaml.contains("{"))
    }
    
    @Test
    fun testToConfixBackwardCompatibility() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = 32768,
            l2Bytes = 262144,
            l3Bytes = null,
            cacheLineBytes = 64,
            coreCount = 8
        )
        
        // Old API should still work
        val json = topology.toConfix()
        
        // Should produce same result as new API
        val newJson = topology.confixJson
        assertTrue(json == newJson, "toConfix() should alias confixJson")
    }
    
    @Test
    fun testAllNullFields() {
        val topology = CpuCacheTopology(
            l1DataBytes = null,
            l1InstructionBytes = null,
            l2Bytes = null,
            l3Bytes = null,
            cacheLineBytes = null,
            coreCount = null
        )
        
        val json = CpuCacheConfix.toJson(topology)
        assertTrue(json.contains("\"l1DataBytes\": null"))
        assertTrue(json.contains("\"l3Bytes\": null"))
        
        val yaml = CpuCacheConfix.toYaml(topology)
        assertTrue(yaml.contains("l1DataBytes: null"))
        assertTrue(yaml.contains("coreCount: null"))
    }
}
