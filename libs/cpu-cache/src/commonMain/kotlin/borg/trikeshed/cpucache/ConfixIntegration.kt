package borg.trikeshed.cpucache

/**
 * Confix-compatible serialization for CpuCacheTopology.
 * 
 * Provides JSON and YAML output formats that are parseable by TrikeShed's Confix tokenizers:
 * - JSON: parseable by JsonScan
 * - YAML: parseable by YamlScan
 * 
 * This unifies cpu-cache output with the TrikeShed Confix parsing ecosystem.
 * Note: Parsing is done by consumers using Confix; this module only produces output.
 */
object CpuCacheConfix {

    /**
     * Serialize to pretty JSON string (2-space indent).
     * Output is parseable by Confix JSON tokenizer (JsonScan).
     * 
     * Format: {"l1DataBytes": 32768, ...} with newlines and indentation
     */
    fun toJson(topology: CpuCacheTopology): String = buildString {
        appendLine("{")
        append("  \"l1DataBytes\": ").append(valueOrNull(topology.l1DataBytes)).appendLine(",")
        append("  \"l1InstructionBytes\": ").append(valueOrNull(topology.l1InstructionBytes)).appendLine(",")
        append("  \"l2Bytes\": ").append(valueOrNull(topology.l2Bytes)).appendLine(",")
        append("  \"l3Bytes\": ").append(valueOrNull(topology.l3Bytes)).appendLine(",")
        append("  \"cacheLineBytes\": ").append(valueOrNull(topology.cacheLineBytes?.toLong())).appendLine(",")
        append("  \"coreCount\": ").append(valueOrNull(topology.coreCount?.toLong()))
        appendLine()
        append("}")
    }
    
    /**
     * Serialize to pretty YAML string (block style).
     * Output is parseable by Confix YAML tokenizer (YamlScan).
     */
    fun toYaml(topology: CpuCacheTopology): String = buildString {
        appendLine("l1DataBytes: ${yamlValue(topology.l1DataBytes)}")
        appendLine("l1InstructionBytes: ${yamlValue(topology.l1InstructionBytes)}")
        appendLine("l2Bytes: ${yamlValue(topology.l2Bytes)}")
        appendLine("l3Bytes: ${yamlValue(topology.l3Bytes)}")
        appendLine("cacheLineBytes: ${yamlValue(topology.cacheLineBytes?.toLong())}")
        append("coreCount: ${yamlValue(topology.coreCount?.toLong())}")
    }
    
    private fun valueOrNull(value: Long?): String = value?.toString() ?: "null"
    
    private fun yamlValue(value: Long?): String = value?.toString() ?: "null"
}

/**
 * Extension property: convert CpuCacheTopology to Confix-compatible JSON.
 */
val CpuCacheTopology.confixJson: String get() = CpuCacheConfix.toJson(this)

/**
 * Extension property: convert CpuCacheTopology to Confix-compatible YAML.
 */
val CpuCacheTopology.confixYaml: String get() = CpuCacheConfix.toYaml(this)

// Backward compatibility: toConfix() outputs compact JSON
fun CpuCacheTopology.toConfix(): String = confixJson
