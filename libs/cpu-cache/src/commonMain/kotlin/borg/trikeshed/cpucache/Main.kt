package borg.trikeshed.cpucache

/**
 * CPU cache interrogation CLI.
 * 
 * Outputs topology in multiple formats via Confix integration:
 * - Default: compact JSON (parseable by Confix JsonScan)
 * - Pass --yaml flag for YAML output (parseable by Confix YamlScan)
 * 
 * Platform-specific [interrogateCpuCache] is resolved at link time.
 */
fun main(args: Array<String>) {
    val topology = interrogateCpuCache()
    
    val format = if (args.isNotEmpty() && args[0] == "--yaml") "yaml" else "json"
    val output = when (format) {
        "yaml" -> CpuCacheConfix.toYaml(topology)
        else -> CpuCacheConfix.toJson(topology)
    }
    
    println(output)
}
